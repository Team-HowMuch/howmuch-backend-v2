package com.amateur.howmuchbackend.adapter.in.web.treasurer;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "howmuch.settlement.notification-outbox.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("정산 요청부터 AI 총무 우선순위 안내까지 실제 HTTP 흐름")
class TreasurerRequestFlowHttpIntegrationTest {
    private static final String PRIORITY_QUESTION = "{\"question\":\"뭐부터 처리해?\"}";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long creator;
    private Long payer;
    private Long connection;
    private Long account;
    private UUID settlement;

    @Test
    @DisplayName("초안으로 오래 둔 정산도 요청 직후에는 기한 초과가 아니고, 요청 후 하루가 지나면 기한 초과로 안내한다")
    void requestedSettlement_elapsedTimeCountsFromRequest() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        creator = insertUser("생성자", now);
        payer = insertUser("참여자", now);
        String creatorToken = login(creator, now);
        String payerToken = login(payer, now);
        requestItemSettlement(creatorToken, now);
        jdbc.update("UPDATE settlements SET created_at = created_at - INTERVAL '3 days' WHERE id = ?", settlement);

        JsonNode payerFresh = data("POST", "/api/v2/treasurer/questions", payerToken, PRIORITY_QUESTION);
        assertThat(payerFresh.path("intent").stringValue()).isEqualTo("PRIORITY_SETTLEMENTS");
        assertThat(payerFresh.path("period").isNull()).isTrue();
        JsonNode payerFreshCard = card(payerFresh);
        assertThat(payerFreshCard.path("direction").stringValue()).isEqualTo("SEND");
        assertThat(payerFreshCard.path("marker").stringValue()).isEqualTo("NONE");
        assertThat(payerFresh.path("answer").stringValue())
                .contains("가장 먼저 확인할 정산은 '요청 흐름 검증'이에요.")
                .doesNotContain("시간 이상 지난");
        assertThat(priorityOf(payerToken)).isEqualTo("SEND_OPEN");
        assertThat(priorityOf(creatorToken)).isEqualTo("RECEIVE_OPEN");

        jdbc.update("UPDATE settlement_links SET created_at = created_at - INTERVAL '25 hours' WHERE settlement_id = ?",
                settlement);

        JsonNode payerOverdue = data("POST", "/api/v2/treasurer/questions", payerToken, PRIORITY_QUESTION);
        assertThat(card(payerOverdue).path("marker").stringValue()).isEqualTo("ATTENTION");
        assertThat(payerOverdue.path("answer").stringValue())
                .endsWith("가장 먼저 확인할 정산은 '요청 흐름 검증'이에요. 25시간 이상 지난 정산이에요. 빨리 정산해 주세요.");
        assertThat(priorityOf(payerToken)).isEqualTo("SEND_OVERDUE");
        assertThat(priorityOf(creatorToken)).isEqualTo("RECEIVE_OVERDUE");
    }

    private void requestItemSettlement(String creatorToken, LocalDateTime now) throws Exception {
        connection = jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,
                active,created_at,updated_at) VALUES (?, ?, ?, true, ?, ?) RETURNING id
                """, Long.class, creator, cipher.encryptConnectedId(creator, "synthetic"), "a".repeat(64), now, now);
        account = jdbc.queryForObject("""
                INSERT INTO accounts(user_id,external_connection_id,organization,candidate_key,
                external_account_key_ciphertext,type,name,masked_number,currency,product_code,active,created_at,updated_at)
                VALUES (?, ?, '0004', ?, ?, 'DEPOSIT_TRUST', '검증계좌', '****0000', 'KRW', '11', true, ?, ?) RETURNING id
                """, Long.class, creator, connection, UUID.randomUUID().toString(),
                cipher.encryptAccountKey(creator, connection, "1234567890"), now, now);
        UUID id = UUID.randomUUID();
        settlement = id;
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,status,receiving_account_id,created_at,updated_at)
                VALUES (?, ?, '요청 흐름 검증', 10000, 'DRAFT', ?, ?, ?)
                """, id, creator, account, now, now);
        insertParticipant(id, new UUID(0, 1), creator, 0, now);
        insertParticipant(id, new UUID(0, 2), payer, 1, now);
        String path = "/api/v2/settlements/" + id;
        JsonNode created = data("POST", path + "/expenses/receipt", creatorToken, """
                {"receiptReference":"treasurer-flow","storeName":"식사","discount":0,"deliveryFee":0,
                 "items":[{"name":"피자","quantity":1,"amount":10000}]}
                """);
        String itemId = created.path("items").get(0).path("itemId").stringValue();
        jdbc.update("UPDATE settlements SET allocation_type='ITEM_ALLOCATION' WHERE id=?", id);
        data("PUT", path + "/my-items/" + itemId, creatorToken, "{\"fraction\":\"HALF\",\"units\":1}");
        data("POST", path + "/my-item-submission", creatorToken, null);
        JsonNode requested = data("POST", path + "/request", creatorToken, null);
        assertThat(requested.path("status").stringValue()).isEqualTo("REQUESTED");
    }

    private JsonNode card(JsonNode answer) {
        JsonNode items = answer.path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("settlementId").stringValue()).isEqualTo(settlement.toString());
        return items.get(0);
    }

    private String priorityOf(String token) throws Exception {
        JsonNode items = data("GET", "/api/v2/treasurer/recommendations", token, null).path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("settlementId").stringValue()).isEqualTo(settlement.toString());
        return items.get(0).path("priority").stringValue();
    }

    private Long insertUser(String name, LocalDateTime now) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,
                role,active,created_at,updated_at) VALUES (?, ?, ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, suffix, name, "trf_" + suffix.replace("-", "").substring(0, 15),
                "test-" + suffix.substring(0, 12), now, now);
    }

    private void insertParticipant(UUID settlementId, UUID id, Long user, int order, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,display_name,
                display_order,created_at,updated_at) VALUES (?, ?, 'REGISTERED_USER', ?, '참여자', ?, ?, ?)
                """, id, settlementId, user, order, now, now);
    }

    private String login(Long user, LocalDateTime now) {
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(user, sessionId, "synthetic", now.plusMinutes(10)));
        return tokens.createAccessToken(users.findById(user).orElseThrow(), sessionId);
    }

    private JsonNode data(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    @AfterEach
    void cleanUpOwnFixtures() {
        if (creator != null) sessions.deleteByUserId(creator);
        if (payer != null) sessions.deleteByUserId(payer);
        if (settlement != null) jdbc.update("DELETE FROM settlements WHERE id=?", settlement);
        if (account != null) {
            jdbc.update("DELETE FROM accounts WHERE id=?", account);
            jdbc.update("DELETE FROM account_records WHERE id=?", account);
        }
        if (connection != null) jdbc.update("DELETE FROM external_connections WHERE id=?", connection);
        if (payer != null) jdbc.update("DELETE FROM users WHERE id=?", payer);
        if (creator != null) jdbc.update("DELETE FROM users WHERE id=?", creator);
    }
}
