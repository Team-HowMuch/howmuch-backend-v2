package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
class SettlementUnpaidSummaryHttpIntegrationTest {
    private static final String PATH = "/api/v2/settlements/unpaid-summary";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private SettlementUnpaidFixtures fixtures;
    private Long creatorId;
    private Long otherId;
    private String creatorToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        fixtures = new SettlementUnpaidFixtures(jdbc);
        creatorId = fixtures.user();
        otherId = fixtures.user();
        creatorToken = accessToken(creatorId);
        otherToken = accessToken(otherId);
    }

    @AfterEach
    void tearDown() {
        sessions.deleteByUserId(creatorId);
        sessions.deleteByUserId(otherId);
        fixtures.close();
    }

    @Test
    void unpaidSummary_unauthenticatedAndMalformedToken_returns401() throws Exception {
        assertUnauthorized(request(PATH, null));
        assertUnauthorized(request(PATH, "not-a-jwt"));
    }

    @Test
    void unpaidSummary_twoAuthenticatedCreators_keepsDataSeparate() throws Exception {
        UUID own = fixtures.settlement(
                creatorId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
        UUID payable = fixtures.share(own, fixtures.participant(own, otherId), 10_000);
        fixtures.deposit(creatorId, own, payable, 3_000);
        fixtures.share(own, fixtures.participant(own, creatorId), 50_000);
        fixtures.settlement(creatorId, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        UUID other = fixtures.settlement(
                otherId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
        fixtures.share(other, fixtures.participant(other, creatorId), 20_000);

        assertSummary(request(PATH, creatorToken), 7_000, 1, 1);
        assertSummary(request(PATH, otherToken), 20_000, 1, 0);
        assertSummary(request(PATH + "?userId=" + otherId, creatorToken), 7_000, 1, 1);
        assertUnauthorized(request(PATH, null));
    }

    @Test
    void unpaidSummary_participantWithoutCreatedSettlements_returnsZeros() throws Exception {
        UUID other = fixtures.settlement(
                otherId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
        fixtures.share(other, fixtures.participant(other, creatorId), 20_000);

        assertSummary(request(PATH, creatorToken), 0, 0, 0);
    }

    @Test
    void unpaidSummary_revokedSession_rejectsStillSignedToken() throws Exception {
        assertSummary(request(PATH, creatorToken), 0, 0, 0);
        sessions.deleteByUserId(creatorId);

        assertUnauthorized(request(PATH, creatorToken));
    }

    @Test
    void unpaidSummary_confirmedPayments_updatesAmountAndParticipation() throws Exception {
        UUID settlementId = fixtures.settlement(
                creatorId, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY
        );
        UUID shareId = fixtures.share(settlementId, fixtures.participant(settlementId, otherId), 10_000);

        assertSummary(request(PATH, creatorToken), 10_000, 1, 0);
        fixtures.deposit(creatorId, settlementId, shareId, 3_000);
        assertSummary(request(PATH, creatorToken), 7_000, 1, 0);
        fixtures.deposit(creatorId, settlementId, shareId, 7_000);
        assertSummary(request(PATH, creatorToken), 0, 0, 0);
        fixtures.deposit(creatorId, settlementId, shareId, 1_000);
        assertSummary(request(PATH, creatorToken), 0, 0, 0);
    }

    @Test
    void unpaidSummary_itemAllocation_distinguishesPendingAndConfirmedAmounts() throws Exception {
        UUID settlementId = fixtures.settlement(
                creatorId, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION
        );
        UUID participantId = fixtures.participant(settlementId, otherId);

        assertSummary(request(PATH, creatorToken), 0, 0, 1);
        UUID shareId = fixtures.share(settlementId, participantId, 10_000);
        fixtures.deposit(creatorId, settlementId, shareId, 3_000);
        assertSummary(request(PATH, creatorToken), 7_000, 1, 1);
        jdbc.update("UPDATE settlements SET status = 'AWAITING_DEPOSIT' WHERE id = ?", settlementId);
        assertSummary(request(PATH, creatorToken), 7_000, 1, 0);
    }

    @Test
    void unpaidSummary_openApi_matchesAuthenticationAndNumericContract() throws Exception {
        var response = request("/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = json.readTree(response.body());
        var operation = document.path("paths").path(PATH).path("get");
        assertThat(operation.path("security").toString()).contains("bearerAuth");
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").has("401")).isTrue();
        assertThat(operation.path("parameters").isMissingNode()).isTrue();

        var schemas = document.path("components").path("schemas");
        assertThat(operation.path("responses").path("200").path("content").has("application/json"))
                .as("200 response: %s", operation.path("responses").path("200"))
                .isTrue();
        String envelopeRef = operation.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").stringValue();
        var envelope = schemas.path(schemaName(envelopeRef));
        String dataRef = envelope.path("properties").path("data").path("$ref").stringValue();
        var dataSchema = schemas.path(schemaName(dataRef));
        var properties = dataSchema.path("properties");
        assertThat(properties.size()).isEqualTo(3);
        assertThat(properties.path("remainingReceivableAmount").path("type").stringValue()).isEqualTo("integer");
        assertThat(properties.path("remainingReceivableAmount").path("format").stringValue()).isEqualTo("int64");
        assertThat(properties.path("unpaidParticipantCount").path("type").stringValue()).isEqualTo("integer");
        assertThat(properties.path("unpaidParticipantCount").path("format").stringValue()).isEqualTo("int32");
        assertThat(properties.path("unconfirmedSettlementCount").path("type").stringValue()).isEqualTo("integer");
        assertThat(properties.path("unconfirmedSettlementCount").path("format").stringValue()).isEqualTo("int32");
        assertThat(dataSchema.path("required").size()).isEqualTo(3);
    }

    private String accessToken(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(
                userId, sessionId, "synthetic-unused-refresh", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
        ));
        return tokens.createAccessToken(users.findById(userId).orElseThrow(), sessionId);
    }

    private HttpResponse<String> request(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertSummary(HttpResponse<String> response, long remaining, int unpaid, int unconfirmed) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json.readTree(response.body());
        assertThat(body.path("success").booleanValue()).isTrue();
        var data = body.path("data");
        assertThat(data.size()).isEqualTo(3);
        assertThat(data.path("remainingReceivableAmount").isIntegralNumber()).isTrue();
        assertThat(data.path("remainingReceivableAmount").longValue()).isEqualTo(remaining);
        assertThat(data.path("unpaidParticipantCount").isIntegralNumber()).isTrue();
        assertThat(data.path("unpaidParticipantCount").intValue()).isEqualTo(unpaid);
        assertThat(data.path("unconfirmedSettlementCount").isIntegralNumber()).isTrue();
        assertThat(data.path("unconfirmedSettlementCount").intValue()).isEqualTo(unconfirmed);
        assertThat(body.has("error")).isFalse();
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        var body = json.readTree(response.body());
        assertThat(body.path("success").booleanValue()).isFalse();
        assertThat(body.path("error").path("status").intValue()).isEqualTo(401);
        assertThat(body.has("data")).isFalse();
    }

    private String schemaName(String ref) {
        assertThat(ref).startsWith("#/components/schemas/");
        return ref.substring(ref.lastIndexOf('/') + 1);
    }
}
