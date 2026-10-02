package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
@DisplayName("배달비 영수증 등록부터 송금 안내까지 실제 HTTP 정합성")
class SettlementDeliveryFeeHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private IssueSettlementParticipantContextPort contexts;
    @Autowired private SettlementLinkTokenPort links;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long creator;
    private Long payer;
    private Long connection;
    private Long account;
    private UUID settlement;

    @ParameterizedTest
    @CsvSource({"10000, 1000, 3003, 6002, 6001", "1, 0, 3, 2, 1"})
    @DisplayName("할인과 홀수 배달비를 저장하고 전원 제출 후 상세·링크·송금 금액을 일치시킨다")
    void deliveryReceipt_registrationToTransfer_preservesFeeAndTransferAmounts(
            long itemAmount, long discount, long deliveryFee, long creatorAmount, long payerAmount
    ) throws Exception {
        long totalAmount = itemAmount - discount + deliveryFee;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        creator = insertUser("생성자", now);
        payer = insertUser("참여자", now);
        String creatorToken = login(creator, now);
        String payerToken = login(payer, now);
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
        settlement = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,status,receiving_account_id,created_at,updated_at)
                VALUES (?, ?, '배달비 HTTP 검증', ?, 'DRAFT', ?, ?, ?)
                """, settlement, creator, totalAmount, account, now, now);
        // 같은 품목 원금이면 UUID가 작은 생성자가 배달비의 마지막 1원을 부담한다.
        UUID creatorParticipant = new UUID(0, 1);
        UUID payerParticipant = new UUID(0, 2);
        insertParticipant(creatorParticipant, creator, 0, now);
        insertParticipant(payerParticipant, payer, 1, now);
        String path = "/api/v2/settlements/" + settlement;
        String receipt = """
                {"receiptReference":"delivery-http","storeName":"식사","discount":%d,"deliveryFee":%d,
                 "items":[{"name":"피자","quantity":1,"amount":%d}]}
                """.formatted(discount, deliveryFee, itemAmount);
        assertThat(request("POST", path + "/expenses/receipt", creatorToken,
                receipt.replace("\"deliveryFee\":" + deliveryFee, "\"deliveryFee\":-1"), null).statusCode()).isEqualTo(400);
        JsonNode created = data("POST", path + "/expenses/receipt", creatorToken, receipt);
        assertThat(created.path("expense").path("deliveryFee").asLong()).isEqualTo(deliveryFee);
        assertThat(created.path("expense").path("amount").asLong()).isEqualTo(totalAmount);
        assertThat(created.path("totalExpenseAmount").asLong()).isEqualTo(totalAmount);
        String itemId = created.path("items").get(0).path("itemId").stringValue();
        assertThat(data("GET", path + "/items", creatorToken, null).path("deliveryFee").asLong()).isEqualTo(deliveryFee);
        assertThat(data("GET", path + "/expenses", creatorToken, null)
                .path("expenses").get(0).path("deliveryFee").asLong()).isEqualTo(deliveryFee);
        jdbc.update("UPDATE settlements SET allocation_type='ITEM_ALLOCATION' WHERE id=?", settlement);

        data("PUT", path + "/my-items/" + itemId, creatorToken, "{\"fraction\":\"HALF\",\"units\":1}");
        data("POST", path + "/my-item-submission", creatorToken, null);
        JsonNode requested = data("POST", path + "/request", creatorToken, null);
        assertThat(requested.path("status").stringValue()).isEqualTo("REQUESTED");
        JsonNode pending = data("PUT", path + "/my-item-selection/" + itemId,
                payerToken, "{\"fraction\":\"HALF\",\"units\":1}");
        assertThat(pending.path("deliveryFeeShare").asLong()).isEqualTo(deliveryFee * (itemAmount / 2) / itemAmount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_shares WHERE settlement_id=?",
                Long.class, settlement)).isZero();

        JsonNode submitted = data("POST", path + "/my-item-selection/submission", payerToken, null);
        assertThat(submitted.path("phase").stringValue()).isEqualTo("TRANSFER_READY");
        assertThat(submitted.path("personalAmount").asLong()).isEqualTo(payerAmount);
        assertThat(submitted.path("deliveryFeeShare").asLong()).isEqualTo(deliveryFee / 2);
        JsonNode creatorItems = data("GET", path + "/my-items", creatorToken, null);
        assertThat(creatorItems.path("personalAmount").asLong()).isEqualTo(creatorAmount);
        assertThat(creatorItems.path("deliveryFeeShare").asLong()).isEqualTo(deliveryFee / 2 + deliveryFee % 2);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM settlement_shares WHERE settlement_id=?",
                Long.class, settlement)).isEqualTo(creatorAmount + payerAmount);

        JsonNode detail = data("GET", path, creatorToken, null);
        assertThat(detail.path("paymentInfo").path("deliveryFee").asLong()).isEqualTo(deliveryFee);
        assertThat(detail.path("paymentSummary").path("expectedAmount").asLong()).isEqualTo(payerAmount);
        assertThat(detail.path("participants").get(0).path("deliveryFeeShare").asLong()).isEqualTo(deliveryFee / 2);
        assertThat(data("GET", path + "/my-transfer-instruction", payerToken, null).path("amount").asLong())
                .isEqualTo(payerAmount);

        String linkPath = URI.create(requested.path("url").stringValue()).getPath();
        String rawLink = linkPath.substring(linkPath.lastIndexOf('/') + 1);
        var context = contexts.issue(links.hash(rawLink), settlement, payerParticipant, now);
        var linked = request("GET", "/api/v2/settlement-links/" + rawLink + "/participants/"
                + payerParticipant + "/items", null, null, context.id());
        assertThat(linked.statusCode()).isEqualTo(200);
        JsonNode linkData = json.readTree(linked.body()).path("data");
        assertThat(linkData.path("deliveryFee").asLong()).isEqualTo(deliveryFee);
        assertThat(linkData.path("deliveryFeeShare").asLong()).isEqualTo(deliveryFee / 2);
        assertThat(linkData.path("personalAmount").asLong()).isEqualTo(payerAmount);
    }

    private Long insertUser(String name, LocalDateTime now) {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,
                role,active,created_at,updated_at) VALUES (?, ?, ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, suffix, name, "fee_" + suffix.replace("-", "").substring(0, 15),
                "test-" + suffix.substring(0, 12), now, now);
    }

    private void insertParticipant(UUID id, Long user, int order, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,display_name,
                display_order,created_at,updated_at) VALUES (?, ?, 'REGISTERED_USER', ?, '참여자', ?, ?, ?)
                """, id, settlement, user, order, now, now);
    }

    private String login(Long user, LocalDateTime now) {
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(user, sessionId, "synthetic", now.plusMinutes(10)));
        return tokens.createAccessToken(users.findById(user).orElseThrow(), sessionId);
    }

    private JsonNode data(String method, String path, String token, String body) throws Exception {
        var response = request(method, path, token, body, null);
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private HttpResponse<String> request(String method, String path, String token, String body, UUID context)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (context != null) request.header("Link-Participant-Context", context.toString());
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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
