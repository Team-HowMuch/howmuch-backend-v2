package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.in.settlement.ProcessRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementExecutionResult;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Spring Boot RANDOM_PORT + java.net.http.HttpClient로 반복 정산 API를 검증한다.
 *
 * <p>가짜 CurrentActor를 쓰지 않는다. 실제 JWT({@link AuthTokenPort})와 Redis 세션({@link AuthSessionPort})을
 * 발급하고 production {@code CurrentActorPort}(SecurityContext 기반)를 통과시킨다. 일정 검증을 위해 주입된
 * {@code Clock}만 테스트에서 제어한다. Docker가 없는 환경을 전제로 {@code RECURRING_TEST_DB_URL}이 지정된
 * 격리 PostgreSQL·Redis에서만 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.settlement.recurring.enabled=true",
        "howmuch.settlement.recurring.poll-enabled=false",
        "howmuch.settlement.netting.enabled=false",
        "spring.batch.job.enabled=false",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false",
        "howmuch.settlement.transfer-reconciliation.enabled=false",
        "howmuch.settlement.category-classification.enabled=false",
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@EnabledIfEnvironmentVariable(named = "RECURRING_TEST_DB_URL", matches = ".+")
@Import(RecurringSettlementHttpIntegrationTest.ClockConfiguration.class)
@DisplayName("반복 정산 실제 인증 HTTP 스모크")
class RecurringSettlementHttpIntegrationTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 13, 0, 0);
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 31, 0, 0);
    private static final String CATEGORY = "HOUSING_TELECOM";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("RECURRING_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_PASSWORD", ""));
        registry.add("spring.data.redis.host", () -> System.getenv().getOrDefault("RECURRING_TEST_REDIS_HOST", "127.0.0.1"));
        registry.add("spring.data.redis.port", () -> System.getenv().getOrDefault("RECURRING_TEST_REDIS_PORT", "6379"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock recurringHttpTestClock() {
            return new MutableClock(CREATED);
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(LocalDateTime start) {
            this.instant = new AtomicReference<>(start.toInstant(ZoneOffset.UTC));
        }

        void at(LocalDateTime time) {
            instant.set(time.toInstant(ZoneOffset.UTC));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private CodefConfigurationFingerprint fingerprint;
    @Autowired private MutableClock clock;
    @Autowired private ProcessRecurringSettlementUseCase worker;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> seededUsers = new java.util.ArrayList<>();

    private long creator;
    private long member;
    private long unrelated;
    private long group;
    private long account;
    private UUID source;
    private String creatorToken;
    private String memberToken;
    private String unrelatedToken;

    @BeforeEach
    void seedRealUsersGroupDraftAndSessions() {
        clock.at(CREATED);
        creator = user("생성자");
        member = user("참여자");
        unrelated = user("무관");
        account = account(creator);
        group = jdbc.queryForObject("""
                INSERT INTO groups(name,owner_id,invite_code,status,creator_id,created_at,updated_at)
                VALUES('recurring-http',?,?,'ACTIVE',?,?,?) RETURNING id
                """, Long.class, creator,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT),
                creator, CREATED, CREATED);
        for (long person : new long[]{creator, member}) {
            jdbc.update("""
                    INSERT INTO group_members(group_id,user_id,role,status,joined_at,created_at,updated_at)
                    VALUES(?,?,?,'ACTIVE',?,?,?)
                    """, group, person, person == creator ? "OWNER" : "MEMBER", CREATED, CREATED, CREATED);
        }
        source = draft(group, creator, member, account);
        creatorToken = token(creator);
        memberToken = token(member);
        unrelatedToken = token(unrelated);
    }

    @AfterEach
    void removeSessions() {
        seededUsers.forEach(sessions::deleteByUserId);
    }

    @Test
    void fullAuthenticatedLifecycle_registrationListHomePublishReplayCancelAndPrivacy() throws Exception {
        // 1) Unauthenticated protected access is 401.
        assertThat(get(groupListPath(), null).statusCode()).isEqualTo(401);

        // 2) An active group member (not the creator) previews the normalized schedule.
        var preview = get("/api/v2/groups/" + group + "/recurring-settlements/schedule-preview"
                + "?frequency=MONTHLY&dayOfMonth=31", memberToken);
        assertThat(preview.statusCode()).isEqualTo(200);
        JsonNode previewData = data(preview);
        assertThat(previewData.path("firstRunDate").stringValue()).isEqualTo("2026-10-31");
        assertThat(previewData.path("timezone").stringValue()).isEqualTo("Asia/Seoul");
        assertThat(previewData.path("requestTime").stringValue()).startsWith("09:00");
        // Project contract: UTC LocalDateTime without offset (see SettlementOpenApiIntegrationTest).
        assertThat(previewData.path("firstRunAt").stringValue()).isEqualTo("2026-10-31T00:00:00");
        assertThat(previewData.path("firstRunAt").stringValue()).doesNotContain("+");

        // 3) Register from the DRAFT with an omitted firstRunDate.
        var created = post(groupListPath(), creatorToken, """
                {"sourceSettlementId":"%s","frequency":"MONTHLY","dayOfMonth":31,"categoryCode":"%s"}
                """.formatted(source, CATEGORY));
        assertThat(created.statusCode()).isEqualTo(200);
        JsonNode createdData = data(created);
        String recurringId = createdData.path("id").stringValue();
        assertThat(createdData.path("categoryCode").stringValue()).isEqualTo(CATEGORY);
        assertThat(createdData.path("nextRunAt").stringValue()).isEqualTo("2026-10-31T00:00:00");

        // 4) List scopes and status. Creator participates, member participates, unrelated is not a member.
        assertThat(data(get(groupListPath() + "?scope=PARTICIPATING&status=ACTIVE", creatorToken))
                .path("items")).hasSize(1);
        assertThat(data(get(groupListPath() + "?scope=OTHER&status=ACTIVE", creatorToken))
                .path("items")).isEmpty();
        // An unknown enum value is a 400, not an empty page.
        assertThat(get(groupListPath() + "?scope=ACTIVE", creatorToken).statusCode()).isEqualTo(400);

        // 5) Home for the creator: own amount, participant flag, D-day, and no secrets.
        var home = get("/api/v2/recurring-settlements/home?limit=5", creatorToken);
        assertThat(home.statusCode()).as("home body: %s", home.body()).isEqualTo(200);
        JsonNode homeItems = data(home);
        assertThat(homeItems).as("home body: %s", home.body()).hasSize(1);
        JsonNode card = homeItems.get(0);
        assertThat(card.path("groupId").longValue()).isEqualTo(group);
        assertThat(card.path("myAmount").longValue()).isEqualTo(12_000L);
        assertThat(card.path("isParticipant").booleanValue()).isTrue();
        assertThat(card.path("daysUntilNextRun").longValue()).isEqualTo(18L);
        assertThat(card.path("displayState").stringValue()).isEqualTo("SCHEDULED");
        assertThat(card.path("category").path("code").stringValue()).isEqualTo(CATEGORY);
        assertThat(home.body()).doesNotContain("****", "accountNumber", "receivingAccountId");
        // The 1..20 bound is enforced server-side.
        assertThat(get("/api/v2/recurring-settlements/home?limit=0", creatorToken).statusCode()).isEqualTo(400);
        assertThat(get("/api/v2/recurring-settlements/home?limit=21", creatorToken).statusCode()).isEqualTo(400);

        // 6) Publish the due occurrence through the real, auth-free worker.
        clock.at(FIRST.plusMinutes(1));
        assertThat(worker.process(UUID.fromString(recurringId), FIRST))
                .isEqualTo(RecurringSettlementExecutionResult.PUBLISHED);
        UUID occurrenceSettlement = jdbc.queryForObject(
                "SELECT settlement_id FROM recurring_settlement_occurrences WHERE recurring_settlement_id=?",
                UUID.class, UUID.fromString(recurringId));
        assertThat(occurrenceSettlement).isNotNull();
        assertThat(jdbc.queryForObject("SELECT source FROM settlement_categories WHERE settlement_id=?",
                String.class, occurrenceSettlement)).isEqualTo("RECURRING");
        assertThat(data(get(groupListPath() + "/" + recurringId, creatorToken))
                .path("nextRunAt").stringValue()).isEqualTo("2026-11-30T00:00:00");

        var homeAfterPublish = data(get("/api/v2/recurring-settlements/home?limit=5", creatorToken));
        assertThat(homeAfterPublish).hasSize(1);
        assertThat(homeAfterPublish.get(0).path("daysUntilNextRun").longValue()).isEqualTo(30L);

        // 7) Identical replay after the original first run elapsed returns the same id.
        clock.at(LocalDateTime.of(2026, 12, 5, 0, 0));
        var replay = post(groupListPath(), creatorToken, """
                {"sourceSettlementId":"%s","frequency":"MONTHLY","dayOfMonth":31,"categoryCode":"%s"}
                """.formatted(source, CATEGORY));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(data(replay).path("id").stringValue()).isEqualTo(recurringId);

        // 8) Cancel as creator, then home excludes it.
        assertThat(post(groupListPath() + "/" + recurringId + "/cancel", creatorToken, "").statusCode()).isEqualTo(200);
        assertThat(data(get("/api/v2/recurring-settlements/home?limit=5", creatorToken))).isEmpty();
        assertThat(data(get(groupListPath() + "?scope=ALL&status=CANCELLED", creatorToken))
                .path("items")).hasSize(1);

        // 9) A non-member cannot read the group list and gets no cross-user home data.
        assertThat(get(groupListPath(), unrelatedToken).statusCode()).isEqualTo(403);
        var unrelatedHome = get("/api/v2/recurring-settlements/home?limit=5", unrelatedToken);
        assertThat(unrelatedHome.statusCode()).isEqualTo(200);
        assertThat(data(unrelatedHome)).isEmpty();
        assertThat(post(groupListPath(), unrelatedToken, """
                {"sourceSettlementId":"%s","frequency":"MONTHLY","dayOfMonth":31,"categoryCode":"%s"}
                """.formatted(source, CATEGORY)).statusCode()).isEqualTo(403);
    }

    @Test
    void authenticatedFormManagement_andOpenApiContract() throws Exception {
        String form = """
                {"title":"직접 HTTP","amount":20001,"categoryCode":"HOUSING_TELECOM",
                 "cadence":{"frequency":"MONTHLY","dayOfMonth":31},
                 "participantUserIds":[%d,%d],"receivingAccountId":%d}
                """.formatted(creator,member,account);
        assertThat(post(groupListPath(), null, form).statusCode()).isEqualTo(401);
        var created=post(groupListPath(),creatorToken,form);
        assertThat(created.statusCode()).isEqualTo(200);
        String id=data(created).path("id").stringValue();
        String path=groupListPath()+"/"+id;
        assertThat(data(created).path("participants").get(0).path("name").stringValue()).isEqualTo("생성자");
        assertThat(data(created).path("receivingAccount").path("maskedNumber").stringValue()).isEqualTo("****1234");
        assertThat(send("PUT",path,memberToken,form).statusCode()).isEqualTo(403);
        assertThat(send("DELETE",path,memberToken,null).statusCode()).isEqualTo(403);
        assertThat(get(path,unrelatedToken).statusCode()).isEqualTo(403);
        var edited=send("PUT",path,creatorToken,form.replace("직접 HTTP","수정 HTTP").replace("20001","30001"));
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(data(edited).path("title").stringValue()).isEqualTo("수정 HTTP");
        assertThat(data(edited).path("id").stringValue()).isEqualTo(id);
        assertThat(send("DELETE",path,creatorToken,null).statusCode()).isEqualTo(200);
        assertThat(send("DELETE",path,creatorToken,null).statusCode()).isEqualTo(200);
        assertThat(get(path,creatorToken).statusCode()).isEqualTo(404);
        assertThat(data(get(groupListPath()+"?status=CANCELLED",creatorToken)).path("items")).isEmpty();
        assertThat(data(get("/api/v2/recurring-settlements/home",creatorToken))).isEmpty();
        var response=get("/v3/api-docs",creatorToken);
        assertThat(response.statusCode()).isEqualTo(200);
        var spec=json.readTree(response.body());
        var operations=spec.path("paths").path("/api/v2/groups/{groupId}/recurring-settlements/{id}");
        assertThat(operations.has("put")).isTrue();assertThat(operations.has("delete")).isTrue();
        var properties=spec.path("components").path("schemas").path("RecurringSettlementWebResponse").path("properties");
        assertThat(properties.has("category")).isTrue();assertThat(properties.has("receivingAccount")).isTrue();
    }

    private String groupListPath() {
        return "/api/v2/groups/" + group + "/recurring-settlements";
    }

    private long user(String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        long id = jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,required_terms_agreed_at,created_at,updated_at)
                VALUES(?,?,?,?,'unused',true,'USER',true,?,?,?) RETURNING id
                """, Long.class, unique, name, "h" + unique.substring(0, 15), "test-" + unique.substring(0, 12),
                CREATED, CREATED, CREATED);
        seededUsers.add(id);
        return id;
    }

    private String token(long userId) {
        User user = users.findById(userId).orElseThrow();
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(userId, sessionId, "synthetic-unused-refresh",
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
        return tokens.createAccessToken(user, sessionId);
    }

    private long account(long creator) {
        long connection = jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)
                VALUES(?,?,?,true,?,?) RETURNING id
                """, Long.class, creator, cipher.encryptConnectedId(creator, "http-connected-" + creator),
                fingerprint.current(), CREATED, CREATED);
        jdbc.update("INSERT INTO connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                + " VALUES(?,'0088','CONNECTED',?,?)", connection, CREATED, CREATED);
        return jdbc.queryForObject("""
                INSERT INTO accounts(user_id,external_connection_id,organization,candidate_key,external_account_key_ciphertext,type,name,masked_number,balance,currency,active,created_at,updated_at,is_default,display_order,product_code)
                VALUES(?,?,'0088',?,?,'DEPOSIT_TRUST','test account','****1234',100000,'KRW',true,?,?,true,0,'11') RETURNING id
                """, Long.class, creator, connection, UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                cipher.encryptAccountKey(creator, connection, "100200" + creator), CREATED, CREATED);
    }

    private UUID draft(long group, long creator, long member, long account) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,status,allocation_type,receiving_account_id,created_at,updated_at)
                VALUES(?,?,'반복 월세',20000,'DRAFT','DUTCH_PAY',?,?,?)
                """, id, creator, account, CREATED, CREATED);
        int order = 0;
        for (long person : new long[]{creator, member}) {
            UUID participant = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,source_group_id,display_name,display_order,created_at,updated_at)
                    VALUES(?,?,?,?,?,'fixture',?,?,?)
                    """, participant, id, person == creator ? "REGISTERED_USER" : "GROUP_MEMBER", person,
                    person == creator ? null : group, order++, CREATED, CREATED);
            jdbc.update("""
                    INSERT INTO settlement_dutch_pay_allocations(id,settlement_id,participant_id,amount,created_at,updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, UUID.randomUUID(), id, participant, person == creator ? 12_000 : 8_000, CREATED, CREATED);
        }
        jdbc.update("""
                INSERT INTO settlement_expenses(id,settlement_id,source,description,amount,display_order,created_at,updated_at)
                VALUES(?,?,'MANUAL','월세',20000,0,?,?)
                """, UUID.randomUUID(), id, CREATED, CREATED);
        return id;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return send("POST", path, token, body);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").booleanValue())
                .as("response body: %s", response.body()).isTrue();
        return body.path("data");
    }
}
