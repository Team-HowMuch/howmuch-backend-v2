import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.sql.DataSource;

/** Calls the running image's actual recap builder without starting another Spring application. */
public final class RecapConsole {
    static ClassLoader loader;
    static final String BASE = "com.amateur.howmuchbackend.";
    static Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    static Object make(String name, Object... args) throws Exception {
        for (var constructor : type(name).getConstructors()) {
            if (constructor.getParameterCount() != args.length) continue;
            boolean matches = true;
            var parameters = constructor.getParameterTypes();
            for (int i=0;i<args.length;i++) if(args[i]!=null && !parameters[i].isInstance(args[i]) && !parameters[i].isPrimitive()) matches=false;
            if (matches) return constructor.newInstance(args);
        }
        throw new IllegalStateException("Unsupported runtime constructor: " + name);
    }
    static Object call(Object object, String name, Object... args) throws Exception {
        for (var method : object.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount()!=args.length) continue;
            boolean matches=true; var types=method.getParameterTypes();
            for(int i=0;i<args.length;i++) if(args[i]!=null && !types[i].isInstance(args[i]) && !types[i].isPrimitive()) matches=false;
            if(matches) return method.invoke(object,args);
        }
        throw new IllegalStateException("Unsupported runtime method: " + name);
    }
    static String env(String primary, String secondary) {
        String result=System.getenv(primary); return result!=null?result:System.getenv(secondary);
    }
    static Object record(String className, ResultSet row) throws Exception {
        var clazz=type(className); var components=clazz.getRecordComponents();
        Object[] values=new Object[components.length]; Class<?>[] types=new Class<?>[components.length];
        for(int i=0;i<components.length;i++) {
            var component=components[i]; types[i]=component.getType();
            String column=component.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            Object value=row.getObject(column);
            if(value instanceof Timestamp timestamp && types[i]==LocalDateTime.class) value=timestamp.toLocalDateTime();
            if(value instanceof java.sql.Date date && types[i]==LocalDate.class) value=date.toLocalDate();
            if(value!=null && types[i].isEnum()) value=Enum.valueOf((Class)types[i],value.toString());
            values[i]=value;
        }
        return clazz.getConstructor(types).newInstance(values);
    }
    public static void main(String[] args) throws Exception {
        Set<String> profiles=Set.of(System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "").split(","));
        if(!profiles.contains("dev") || profiles.contains("prod") || profiles.contains("production")) throw new IllegalStateException("Dev only");
        String mode=args[0]; Long owner=Long.valueOf(args[1]); YearMonth month=YearMonth.parse(args[2]);
        if(!Set.of(85L,86L,87L,88L).contains(owner) || !Set.of("PREVIEW","GENERATE").contains(mode)) throw new IllegalArgumentException("Unsupported actor or mode");
        YearMonth current=YearMonth.now(ZoneId.of("Asia/Seoul"));
        if(month.isAfter(current) || (mode.equals("GENERATE") && !month.isBefore(current))) throw new IllegalArgumentException("Only closed months can be published");
        String url=env("DB_URL","SPRING_DATASOURCE_URL");
        if(!url.matches("jdbc:postgresql://[^/]+/howmuch_v2_dev(?:\\?.*)?")) throw new IllegalStateException("Dev database only");
        Path directory=Path.of(args[3]).resolve("runtime"); Files.createDirectories(directory);
        Files.createDirectories(directory.resolve("classes"));
        List<URL> urls=new ArrayList<>(); urls.add(directory.resolve("classes").toUri().toURL());
        try(ZipFile archive=new ZipFile("/app/app.jar")) {
            var entries=archive.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement(); String name=entry.getName(); Path target;
                if(name.startsWith("BOOT-INF/classes/") && name.endsWith(".class")) target=directory.resolve("classes").resolve(name.substring(17));
                else if(name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) { target=directory.resolve("lib").resolve(Path.of(name).getFileName()); urls.add(target.toUri().toURL()); }
                else continue;
                if(!target.normalize().startsWith(directory)) throw new IllegalStateException("Invalid runtime entry");
                Files.createDirectories(target.getParent()); try(InputStream input=archive.getInputStream(entry)) {Files.copy(input,target,StandardCopyOption.REPLACE_EXISTING);}
            }
        }
        loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()); Thread.currentThread().setContextClassLoader(loader);
        var dataSource=make("org.postgresql.ds.PGSimpleDataSource");
        call(dataSource,"setUrl",url);call(dataSource,"setUser",env("DB_USERNAME","SPRING_DATASOURCE_USERNAME"));call(dataSource,"setPassword",env("DB_PASSWORD","SPRING_DATASOURCE_PASSWORD"));
        try(Connection connection=((DataSource)dataSource).getConnection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(Statement sql=connection.createStatement()) {sql.execute("SET LOCAL statement_timeout='25s'");sql.execute("SELECT pg_advisory_xact_lock(7472421)");}
            var shared=make("org.springframework.jdbc.datasource.SingleConnectionDataSource",connection,true);
            var jdbc=make("org.springframework.jdbc.core.JdbcTemplate",shared);
            var named=make("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",jdbc);
            var json=make("tools.jackson.databind.ObjectMapper");
            var snapshots=make(BASE+"adapter.out.persistence.recap.MonthlyRecapPersistenceAdapter",named,json);
            var users=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{type(BASE+"application.port.out.user.LoadUserPort")},(proxy,method,arguments)->{
                if(!method.getName().equals("findById") && !method.getName().equals("findByIdForUpdate")) throw new UnsupportedOperationException();
                try(var sql=connection.prepareStatement("SELECT * FROM users WHERE id=? AND active")) {
                    sql.setLong(1,(Long)arguments[0]);try(var row=sql.executeQuery()){return row.next()?Optional.of(record(BASE+"domain.user.User",row)):Optional.empty();}
                }
            });
            var codes=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{type(BASE+"application.port.out.commoncode.LoadCommonCodePort")},(proxy,method,arguments)->{
                if(!method.getName().equals("findActiveByGroupCode")) throw new UnsupportedOperationException();
                List<Object> result=new ArrayList<>();try(var sql=connection.prepareStatement("SELECT d.*,d.use_yn AS active FROM common_code_detail d WHERE d.group_code=? AND d.use_yn AND EXISTS(SELECT 1 FROM common_code_group g WHERE g.group_code=d.group_code AND g.use_yn)")) {
                    sql.setString(1,(String)arguments[0]);try(var rows=sql.executeQuery()){while(rows.next())result.add(record(BASE+"domain.commoncode.CommonCode",rows));}
                }return result;
            });
            var catalog=make(BASE+"application.settlement.service.SettlementCategoryCatalog",codes);
            var categories=make(BASE+"application.settlement.service.MySettlementCategoryReader",make(BASE+"adapter.out.persistence.settlement.SettlementUserCategoryJdbcAdapter",jdbc),catalog);
            var source=make(BASE+"adapter.out.persistence.recap.RecapSourcePersistenceAdapter",named,make(BASE+"adapter.out.persistence.recap.mapper.RecapAllocationFactsMapper",json));
            var builder=make(BASE+"application.recap.service.BuildMonthlyRecapService",users,source,snapshots,
                make(BASE+"application.recap.policy.RecapSummaryPolicy"),make(BASE+"application.settlement.policy.SettlementItemAllocationPolicy"),
                make(BASE+"application.recap.policy.RecapComparisonPolicy"),make(BASE+"application.recap.policy.RecapCompanionPolicy"),categories,make(BASE+"application.recap.policy.RecapCardPolicy"));
            Optional<?> existing=(Optional<?>)call(snapshots,"find",owner,month);
            Optional<?> built=existing.isPresent()?existing:(Optional<?>)call(builder,"build",owner,month,LocalDateTime.now(ZoneOffset.UTC));
            if(built.isEmpty()){connection.rollback();System.out.println("{\"status\":\"NO_COMPLETED_DATA\"}");return;}
            String status=existing.isPresent()?"EXISTING":"PREVIEW";
            if(existing.isEmpty() && mode.equals("GENERATE")) {call(snapshots,"insertIfAbsent",built.get());connection.commit();status="CREATED";} else connection.rollback();
            Object result=type(BASE+"application.port.in.recap.result.MonthlyRecapResult").getMethod("from",type(BASE+"application.recap.model.MonthlyRecap")).invoke(null,built.get());
            Object web=type(BASE+"adapter.in.web.recap.dto.response.MonthlyRecapWebResponse").getMethod("from",result.getClass()).invoke(null,result);
            System.out.println(call(json,"writeValueAsString",Map.of("status",status,"data",web)));
        }
    }
}
