package com.amateur.howmuchbackend.adapter.in.web.certificate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.certificate.model.CertificateProviderResult;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateProviderAccountPort;

import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=local,codef-mock",
            "howmuch.auth.sms.discord.webhook-url=https://discord.example.test/synthetic",
            "management.server.port=0",
            "howmuch.certificate.enabled=true",
            "ACCOUNT_CREDENTIAL_LOCAL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "howmuch.codef.connected-id-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "howmuch.messaging.kafka.enabled=true",
            "howmuch.certificate.local-wrapping-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "howmuch.certificate.confirmation-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "howmuch.certificate.worker-delay-ms=100",
            "howmuch.certificate.relay-delay-ms=100"
        })
class CertificateHttpIntegrationTest {
    @Container
    static final GenericContainer<?> DB =
            new GenericContainer<>(DockerImageName.parse("postgres:16.14"))
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                    .withEnv("POSTGRES_DB", "certificate_http")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static final KafkaContainer BROKER =
            new KafkaContainer("apache/kafka:4.2.2")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + DB.getHost()
                                + ":"
                                + DB.getMappedPort(5432)
                                + "/certificate_http");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", BROKER::getBootstrapServers);
    }

    @LocalServerPort int port;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort tokens;

    @org.springframework.beans.factory.annotation.Autowired
    com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort sessions;

    @org.springframework.beans.factory.annotation.Autowired
    com.amateur.howmuchbackend.application.port.out.user.LoadUserPort users;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    String mobileToken;

    @BeforeEach
    void resetToken() {
        mobileToken = null;
        // Each test has its own request budget in the dedicated Redis container.
        var limitKeys = redis.keys("certificate:create:*");
        if (limitKeys != null && !limitKeys.isEmpty()) redis.delete(limitKeys);
    }

    void loginFixture() {
        String unique = UUID.randomUUID().toString();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        Long userId =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)"
                            + " VALUES(?,'승인 테스트',?,?,'unused',true,'USER',true,?,?) RETURNING id",
                        Long.class,
                        unique,
                        "cert_" + unique.replace("-", "").substring(0, 10),
                        unique.substring(0, 20),
                        now,
                        now);
        var user = users.findById(userId).orElseThrow();
        String session = UUID.randomUUID().toString();
        sessions.save(
                new com.amateur.howmuchbackend.domain.auth.session.AuthSession(
                        userId, session, "synthetic-refresh", now.plusMinutes(30)));
        mobileToken = tokens.createAccessToken(user, session);
    }

    String approveQr(String base, String id, String pc) throws Exception {
        var qr = request("POST", base + "/" + id + "/qr", null, pc, null);
        loginFixture();
        var body =
                Map.of(
                        "sessionId",
                        id,
                        "qrToken",
                        json.readTree(qr.body()).at("/data/qrToken").asText(),
                        "idempotencyKey",
                        UUID.randomUUID().toString());
        var reservation =
                request(
                        "POST",
                        "/api/v2/certificate-claims",
                        json.writeValueAsString(body),
                        null,
                        null);
        assertThat(reservation.statusCode()).isEqualTo(201);
        String claim = json.readTree(reservation.body()).at("/data/claimId").asText();
        var approved =
                request(
                        "POST",
                        "/api/v2/certificate-claims/" + id + "/" + claim + "/decision",
                        "{\"approved\":true}",
                        null,
                        null);
        assertThat(approved.statusCode()).isEqualTo(200);
        return claim;
    }

    @MockitoBean CertificateProviderAccountPort provider;
    @MockitoBean com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort banks;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void topics() throws Exception {
        try (var admin =
                AdminClient.create(Map.of("bootstrap.servers", BROKER.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(
                                            "certificate.registration.command.v1", 1, (short) 1),
                                    new NewTopic(
                                            "certificate.registration.result.v1", 1, (short) 1),
                                    new NewTopic(
                                            "certificate.registration.command.v1.dlt",
                                            1,
                                            (short) 1),
                                    new NewTopic(
                                            "certificate.registration.result.v1.dlt",
                                            1,
                                            (short) 1)))
                    .all()
                    .get(15, TimeUnit.SECONDS);
        }
    }

    HttpResponse<String> request(String method, String path, String body, String pc, String upload)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (mobileToken != null) b.header("Authorization", "Bearer " + mobileToken);
        if (pc != null) b.header("X-PC-Capability", pc);
        if (upload != null) b.header("X-Upload-Capability", upload);
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void migrationConvertsInactiveBanksBeforeTheyAreReenabled() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    assertThat(statement.executeUpdate(
                                    "UPDATE common_code_detail SET use_yn=false, attr3='1' "
                                            + "WHERE group_code='BANK' AND code IN ('0020','0031','0003','0023')"))
                            .isEqualTo(4);
                }
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                        connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V54__set_bank_authentication_levels.sql"));
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "UPDATE common_code_detail SET use_yn=true "
                                    + "WHERE group_code='BANK' AND code IN ('0020','0031','0003','0023')");
                    try (var rows = statement.executeQuery(
                            "SELECT code, attr3 FROM common_code_detail WHERE group_code='BANK' "
                                    + "AND code IN ('0020','0031','0003','0023')")) {
                        var expected = Map.of("0020", "3", "0031", "3", "0003", "2", "0023", "1");
                        int count = 0;
                        while (rows.next()) {
                            assertThat(rows.getString("attr3")).isEqualTo(expected.get(rows.getString("code")));
                            count++;
                        }
                        assertThat(count).isEqualTo(4);
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void legacyBankCatalogKeepsBooleanResponseContract() throws Exception {
        loginFixture();
        var response = request("GET", "/api/v2/accounts/banks", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var banks = json.readTree(response.body()).path("data");
        assertThat(banks.size()).isPositive();
        for (var bank : banks) {
            assertThat(bank.path("requiresAccountCredentials").isBoolean()).isTrue();
            assertThat(bank.has("authenticationLevel")).isFalse();
        }
    }

    @Test
    void pcHttpFlowWaitsForMobileApprovalBeforeProviderCall() throws Exception {
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-http-fixture"));
        String base = "/api/v2/certificate-registrations";
        var catalog = request("GET", "/api/v3/banks", null, null, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        var institutions = json.readTree(catalog.body()).path("data");
        assertThat(institutions.size()).isPositive();
        var levels = new HashSet<Integer>();
        for (var institution : institutions) {
            levels.add(institution.path("authenticationLevel").asInt());
            assertThat(institution.has("requiresAccountCredentials")).isFalse();
            if (institution.path("organization").asText().equals("0020")
                    || institution.path("organization").asText().equals("0031")) {
                assertThat(institution.path("authenticationLevel").asInt()).isEqualTo(3);
            }
        }
        assertThat(levels).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(
                        request(
                                        "GET",
                                        "/api/v2/certificate-registrations/institutions",
                                        null,
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(400);
        assertThat(request("POST", base, "{\"organization\":\"9999\"}", null, null).statusCode())
                .isEqualTo(400);
        var created = request("POST", base, "{\"organization\":\"0020\"}", null, null);
        assertThat(created.statusCode()).isEqualTo(201);
        var data = json.readTree(created.body()).path("data");
        String id = data.path("id").asText(),
                pc = data.path("pcCapability").asText(),
                upload = data.path("uploadCapability").asText();
        assertThat(request("GET", base + "/" + id, null, "wrong", null).statusCode())
                .isEqualTo(404);
        assertThat(
                        request(
                                        "POST",
                                        base + "/" + id + "/upload",
                                        "{\"derFile\":\"AQ==\",\"keyFile\":\"Ag==\",\"password\":\"synthetic-password\"}",
                                        pc,
                                        upload)
                                .statusCode())
                .isEqualTo(400);
        verifyNoInteractions(provider);
        var submitted =
                request(
                        "POST",
                        base + "/" + id + "/upload",
                        "{\"derFile\":\"AQ==\",\"keyFile\":\"Ag==\",\"password\":\"synthetic-password\",\"accountNumber\":\"123456\",\"accountPassword\":\"1234\"}",
                        pc,
                        upload);
        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(submitted.body()).doesNotContain("synthetic-password", pc, upload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String status = "";
        while (System.nanoTime() < deadline) {
            status =
                    json.readTree(request("GET", base + "/" + id, null, pc, null).body())
                            .at("/data/status")
                            .asText();
            if (status.equals("READY_FOR_CLAIM")) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("READY_FOR_CLAIM");
        verifyNoInteractions(provider);
        var qr = request("POST", base + "/" + id + "/qr", null, pc, null);
        assertThat(qr.statusCode()).isEqualTo(200);
        assertThat(json.readTree(qr.body()).at("/data/qrToken").asText()).hasSize(43);
        assertThat(qr.body()).doesNotContain(pc, upload, "connected-http-fixture");
        assertThat(request("POST", "/api/v2/certificate-claims", "{}", null, null).statusCode())
                .isEqualTo(401);
        assertThat(
                        request(
                                        "POST",
                                        base + "/" + id + "/upload",
                                        "x".repeat(768 * 1024 + 1),
                                        pc,
                                        upload)
                                .statusCode())
                .isEqualTo(413);
        assertThat(request("DELETE", base + "/" + id, null, pc, null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({
        "CF-12302,BANK_CERTIFICATE_PASSWORD_INVALID",
        "CF-13031,BANK_ACCOUNT_PASSWORD_INVALID",
        "CF-13032,BANK_ACCOUNT_PASSWORD_LOCKED",
        "CF-12806,BANK_CERTIFICATE_EXPIRED",
        "CF-99998,CODEF_UNMAPPED_ERROR"
    })
    void providerRejectionReachesThePcAsOurCodeAndMessage(String code, String commonCode)
            throws Exception {
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.rejected(code));
        String base = "/api/v2/certificate-registrations";
        var created = request("POST", base, "{\"organization\":\"0020\"}", null, null);
        assertThat(created.statusCode()).isEqualTo(201);
        var data = json.readTree(created.body()).path("data");
        String id = data.path("id").asText(),
                pc = data.path("pcCapability").asText(),
                upload = data.path("uploadCapability").asText();
        var submitted =
                request(
                        "POST",
                        base + "/" + id + "/upload",
                        "{\"derFile\":\"AQ==\",\"keyFile\":\"Ag==\",\"password\":\"synthetic-password\",\"accountNumber\":\"123456\",\"accountPassword\":\"1234\"}",
                        pc,
                        upload);
        assertThat(submitted.statusCode()).isEqualTo(200);
        approveQr(base, id, pc);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        tools.jackson.databind.JsonNode state = null;
        while (System.nanoTime() < deadline) {
            var response = request("GET", base + "/" + id, null, pc, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).doesNotContain(code, "synthetic-password", pc, upload);
            state = json.readTree(response.body()).path("data");
            if (state.path("status").asText().equals("FAILED")) break;
            Thread.sleep(100);
        }
        assertThat(state.path("status").asText()).isEqualTo("FAILED");
        assertThat(state.path("failureCode").asText()).isEqualTo(commonCode);
        assertThat(state.path("failureMessage").asText())
                .isEqualTo(
                        com.amateur.howmuchbackend.domain.external.status
                                .ExternalConnectionStatusCode.valueOf(commonCode)
                                .getMessage());
        verify(provider, times(1)).register(eq("0020"), any(), eq(true));
        assertThat(request("POST", base + "/" + id + "/qr", null, pc, null).statusCode())
                .isEqualTo(409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0020", "0031"})
    void authenticatedYesStoresConnectionAndBothClientsObserveBound(String organization)
            throws Exception {
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-http-approved"));
        String base = "/api/v2/certificate-registrations";
        var catalog =
                json.readTree(request("GET", "/api/v3/banks", null, null, null).body())
                        .path("data");
        var institution =
                java.util.stream.StreamSupport.stream(catalog.spliterator(), false)
                        .filter(i -> i.path("organization").asText().equals(organization))
                        .findFirst()
                        .orElseThrow();
        assertThat(institution.path("authenticationLevel").asInt()).isEqualTo(3);
        assertThat(institution.has("requiresAccountCredentials")).isFalse();
        var created =
                json.readTree(
                                request(
                                                "POST",
                                                base,
                                                json.writeValueAsString(
                                                        Map.of("organization", organization)),
                                                null,
                                                null)
                                        .body())
                        .path("data");
        String id = created.path("id").asText(),
                pc = created.path("pcCapability").asText(),
                upload = created.path("uploadCapability").asText();
        assertThat(
                        request(
                                        "POST",
                                        base + "/" + id + "/upload",
                                        "{\"derFile\":\"AQ==\",\"keyFile\":\"Ag==\",\"password\":\"synthetic-password\"}",
                                        pc,
                                        upload)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        request(
                                        "POST",
                                        base + "/" + id + "/upload",
                                        "{\"derFile\":\"AQ==\",\"keyFile\":\"Ag==\",\"password\":\"synthetic-password\",\"accountNumber\":\"123456\",\"accountPassword\":\"1234\"}",
                                        pc,
                                        upload)
                                .statusCode())
                .isEqualTo(200);
        verifyNoInteractions(provider);
        String claim = approveQr(base, id, pc);
        String mobilePath = "/api/v2/certificate-claims/" + id + "/" + claim;
        tools.jackson.databind.JsonNode state = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var response = request("GET", mobilePath, null, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .doesNotContain("connected-http-approved", "synthetic-password");
            state = json.readTree(response.body()).path("data");
            if (state.path("status").asText().equals("BOUND")) break;
            Thread.sleep(100);
        }
        assertThat(state.path("status").asText()).isEqualTo("BOUND");
        assertThat(state.path("connectionId").asLong()).isPositive();
        assertThat(
                        json.readTree(request("GET", base + "/" + id, null, pc, null).body())
                                .at("/data/status")
                                .asText())
                .isEqualTo("BOUND");
        var list = request("GET", "/api/v2/accounts/connections", null, null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).doesNotContain("connected-http-approved");
        assertThat(json.readTree(list.body()).path("data").size()).isEqualTo(1);
        when(banks.findAccounts(eq("connected-http-approved"), eq(organization), isNull(), any()))
                .thenReturn(List.of());
        var accounts =
                request(
                        "GET",
                        "/api/v2/accounts/connections/"
                                + state.path("connectionId").asLong()
                                + "/accounts?organization="
                                + organization,
                        null,
                        null,
                        null);
        assertThat(accounts.statusCode()).isEqualTo(200);
        verify(banks)
                .findAccounts(
                        eq("connected-http-approved"),
                        eq(organization),
                        isNull(),
                        argThat(
                                c ->
                                        c != null
                                                && c.number().equals("123456")
                                                && c.password().equals("1234")));
        assertThat(
                        request("POST", mobilePath + "/decision", "{\"approved\":true}", null, null)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(
                                        "POST",
                                        mobilePath + "/decision",
                                        "{\"approved\":false}",
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(409);
        assertThat(request("POST", mobilePath + "/decision", "{}", null, null).statusCode())
                .isEqualTo(400);
        mobileToken = null;
        assertThat(request("GET", mobilePath, null, null, null).statusCode()).isEqualTo(401);
        assertThat(
                        request("POST", mobilePath + "/decision", "{\"approved\":true}", null, null)
                                .statusCode())
                .isEqualTo(401);
        loginFixture();
        assertThat(request("GET", mobilePath, null, null, null).statusCode()).isEqualTo(404);
        assertThat(
                        request("POST", mobilePath + "/decision", "{\"approved\":true}", null, null)
                                .statusCode())
                .isEqualTo(404);
        verify(provider, times(1)).register(eq(organization), any(), eq(true));
    }

    @Test
    void failedPairingRetriesAndMobileOwnerCanCancel() throws Exception {
        var initial = request("POST", "/api/v2/certificate-pairings", "{}", null, null);
        var p = json.readTree(initial.body()).path("data");
        String root = "/api/v2/certificate-pairings/" + p.path("id").asText();
        String pc = p.path("pcCapability").asText();
        assertThat(request("DELETE", root, null, null, null).statusCode()).isEqualTo(401);
        loginFixture();
        assertThat(request("POST", root + "/claim",
                json.writeValueAsString(Map.of("qrToken", p.path("qrToken").asText(), "organization", "0020")),
                null, null).statusCode()).isEqualTo(200);
        String ownerToken = mobileToken;
        var first = request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null)
                .statusCode()).isEqualTo(409);
        UUID firstId = UUID.fromString(json.readTree(first.body()).at("/data/id").asText());
        jdbc.update("UPDATE certificate_registration_sessions SET status='UNKNOWN' WHERE id=?", firstId);
        assertThat(request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null)
                .statusCode()).isEqualTo(409);
        jdbc.update("UPDATE certificate_registration_sessions SET status='FAILED' WHERE id=?", firstId);
        var retry = request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(json.readTree(retry.body()).at("/data/id").asText()).isNotEqualTo(firstId.toString());
        loginFixture();
        assertThat(request("DELETE", root, null, null, null).statusCode()).isEqualTo(404);
        mobileToken = ownerToken;
        assertThat(request("DELETE", root, null, null, null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", root, null, null, null).statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET", root + "/pc", null, pc, null).body())
                .at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null)
                .statusCode()).isEqualTo(409);
    }

    @Test
    void earlyPairingAuthenticatesBeforeFinalMobileApproval() throws Exception {
        var initial = request("POST", "/api/v2/certificate-pairings", "{}", null, null);
        assertThat(initial.statusCode()).isEqualTo(201);
        var p = json.readTree(initial.body()).path("data");
        String pair = p.path("id").asText(), pc = p.path("pcCapability").asText();
        String root = "/api/v2/certificate-pairings/" + pair;
        assertThat(
                        request(
                                        "POST",
                                        root + "/claim",
                                        json.writeValueAsString(
                                                Map.of("qrToken", p.path("qrToken").asText(), "organization", "0020")),
                                        null,
                                        null)
                                .statusCode())
                .isEqualTo(401);
        assertThat(request("GET", root + "/pc", null, "x".repeat(43), null).statusCode())
                .isEqualTo(404);
        loginFixture();
        assertThat(request("POST", root + "/claim", json.writeValueAsString(Map.of("qrToken", p.path("qrToken").asText())), null, null).statusCode()).isEqualTo(400);
        var paired =
                request(
                        "POST",
                        root + "/claim",
                        json.writeValueAsString(Map.of("qrToken", p.path("qrToken").asText(), "organization", "0020")),
                        null,
                        null);
        assertThat(paired.statusCode()).isEqualTo(200);
        assertThat(json.readTree(paired.body()).at("/data/organization").asText()).isEqualTo("0020");
        assertThat(json.readTree(request("GET", root + "/pc", null, pc, null).body()).at("/data/organization").asText()).isEqualTo("0020");
        assertThat(request("POST", root + "/registrations", "{\"organization\":\"0004\"}", pc, null).statusCode()).isEqualTo(409);
        assertThat(json.readTree(paired.body()).at("/data/status").asText()).isEqualTo("PAIRED");
        var started =
                request("POST", root + "/registrations", "{\"organization\":\"0020\"}", pc, null);
        assertThat(started.statusCode()).isEqualTo(201);
        var registration = json.readTree(started.body()).path("data");
        String id = registration.path("id").asText(),
                sessionPc = registration.path("pcCapability").asText();
        when(provider.register(eq("0020"), any(), eq(true)))
                .thenReturn(CertificateProviderResult.connected("pair-http-" + UUID.randomUUID()));
        var upload =
                request(
                        "POST",
                        "/api/v2/certificate-registrations/" + id + "/upload",
                        json.writeValueAsString(
                                Map.of(
                                        "derFile",
                                        "AQ==",
                                        "keyFile",
                                        "Ag==",
                                        "password",
                                        "synthetic-password",
                                        "accountNumber",
                                        "123456",
                                        "accountPassword",
                                        "1234",
                                        "birthDate",
                                        "19900101")),
                        sessionPc,
                        registration.path("uploadCapability").asText());
        assertThat(upload.statusCode()).isEqualTo(200);
        String state = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            state =
                    json.readTree(request("GET", root, null, null, null).body())
                            .at("/data/status")
                            .asText();
            if (state.equals("READY_FOR_CLAIM")) break;
            Thread.sleep(100);
        }
        assertThat(state).isEqualTo("READY_FOR_CLAIM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_registration_consents WHERE"
                                        + " session_id=?",
                                Integer.class,
                                UUID.fromString(id)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_provider_connections WHERE"
                                        + " session_id=? AND external_connection_id IS NOT NULL",
                                Integer.class,
                                UUID.fromString(id)))
                .isZero();
        var claim = request("POST", root + "/approval", "{}", null, null);
        assertThat(claim.statusCode()).isEqualTo(200);
        String claimId = json.readTree(claim.body()).at("/data/claimId").asText();
        var bound =
                request(
                        "POST",
                        "/api/v2/certificate-claims/" + id + "/" + claimId + "/decision",
                        "{\"approved\":true}",
                        null,
                        null);
        assertThat(bound.statusCode()).isEqualTo(200);
        assertThat(json.readTree(bound.body()).at("/data/status").asText()).isEqualTo("BOUND");
        assertThat(json.readTree(bound.body()).at("/data/connectionId").asLong()).isPositive();
        assertThat(request("DELETE", root, null, null, null).statusCode()).isEqualTo(409);
        assertThat(
                        json.readTree(request("GET", root, null, null, null).body())
                                .at("/data/status")
                                .asText())
                .isEqualTo("BOUND");
    }
}
