package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.TimeZone;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
                "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZg==",
                "howmuch.settlement.notification-outbox.enabled=false"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실제 서버 UTC 기동과 결제내역 HTTP 계약")
class SettlementPaymentInfoHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private SettlementLinkTokenPort links;
    @Autowired private ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    private Long user;
    private Long connection;
    private Long account;
    private UUID settlement;

    @ParameterizedTest
    @ValueSource(strings = {"2026-08-31T15:05:00", "2026-12-31T15:05:00"})
    @DisplayName("기동 시간대와 관계없이 월말·연말 거래 시각을 저장·상세·링크까지 보존한다")
    void paymentExpense_realHttp_preservesMonthEndTransaction(String paidAt) throws Exception {
        assertThat(TimeZone.getDefault().toZoneId().normalized()).isEqualTo(ZoneOffset.UTC);
        String suffix = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        user = jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,
                role,active,created_at,updated_at)
                VALUES (?, 'HTTP 검증', ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, suffix, "http_" + suffix.replace("-", "").substring(0, 15), "test-" + suffix.substring(0, 12), now, now);
        connection = jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,
                active,created_at,updated_at) VALUES (?, ?, ?, true, ?, ?) RETURNING id
                """, Long.class, user, cipher.encryptConnectedId(user, "synthetic"), "a".repeat(64), now, now);
        account = jdbc.queryForObject("""
                INSERT INTO accounts(user_id,external_connection_id,organization,candidate_key,
                external_account_key_ciphertext,type,name,masked_number,currency,active,created_at,updated_at)
                VALUES (?, ?, '0004', ?, ?, 'DEPOSIT_TRUST', '검증계좌', '****0000', 'KRW', true, ?, ?) RETURNING id
                """, Long.class, user, connection, suffix, cipher.encryptAccountKey(user, connection, "synthetic"), now, now);
        Long transaction = jdbc.queryForObject("""
                INSERT INTO account_transactions(account_id,external_transaction_key,transaction_type,
                amount,transacted_at,description) VALUES (?, ?, 'EXPENSE', 10000, CAST(? AS timestamp), '행복식당') RETURNING id
                """, Long.class, account, "b".repeat(64), paidAt);
        settlement = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,status,created_at,updated_at)
                VALUES (?, ?, 'HTTP 정산', 10000, 'DRAFT', ?, ?)
                """, settlement, user, now, now);
        String session = UUID.randomUUID().toString();
        sessions.save(new AuthSession(user, session, "synthetic", now.plusMinutes(10)));
        String token = tokens.createAccessToken(users.findById(user).orElseThrow(), session);
        try {
            String path = "/api/v2/settlements/" + settlement;
            HttpResponse<String> imported = request("POST", path + "/expenses/payment-history", token,
                    "{\"accountId\":" + account + ",\"transactionId\":" + transaction + "}");
            assertThat(imported.statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT paid_at::text FROM settlement_expenses WHERE settlement_id=?",
                    String.class, settlement)).isEqualTo(paidAt.replace('T', ' '));
            HttpResponse<String> detailResponse = request("GET", path, token, null);
            assertThat(detailResponse.statusCode()).isEqualTo(200);
            JsonNode detail = json.readTree(detailResponse.body()).path("data").path("paymentInfo");
            assertThat(detail.path("storeName").stringValue()).isEqualTo("행복식당");
            assertThat(detail.path("paidAt").stringValue()).isEqualTo(paidAt);
            assertThat(request("GET", path, null, null).statusCode()).isEqualTo(401);
            // 요청·납부 설정을 다시 구성하지 않고 유효한 공유 링크 테스트 데이터를 준비한다.
            jdbc.update("UPDATE settlements SET status='REQUESTED', allocation_type='ITEM_ALLOCATION' WHERE id=?", settlement);
            var link = links.issue(now);
            jdbc.update("""
                    INSERT INTO settlement_links(id,settlement_id,token_hash,status,expires_at,created_at,updated_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                    """, UUID.randomUUID(), settlement, link.tokenHash(), now.plusMinutes(10), now, now);
            HttpResponse<String> entryResponse = request("GET", "/api/v2/settlement-links/" + link.rawToken(), null, null);
            assertThat(entryResponse.statusCode()).isEqualTo(200);
            JsonNode entry = json.readTree(entryResponse.body()).path("data");
            assertThat(entry.path("storeName").stringValue()).isEqualTo(detail.path("storeName").stringValue());
            assertThat(entry.path("paidAt").stringValue()).isEqualTo(detail.path("paidAt").stringValue());
        } finally {
            sessions.deleteByUserId(user);
        }
    }

    @AfterEach
    void cleansUpOwnFixtures() {
        if (user != null) {
            sessions.deleteByUserId(user);
        }
        if (settlement != null) {
            jdbc.update("DELETE FROM settlements WHERE id=?", settlement);
        }
        if (account != null) {
            jdbc.update("DELETE FROM account_transactions WHERE account_id=?", account);
            jdbc.update("DELETE FROM accounts WHERE id=?", account);
            jdbc.update("DELETE FROM account_records WHERE id=?", account);
        }
        if (connection != null) {
            jdbc.update("DELETE FROM external_connections WHERE id=?", connection);
        }
        if (user != null) {
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
