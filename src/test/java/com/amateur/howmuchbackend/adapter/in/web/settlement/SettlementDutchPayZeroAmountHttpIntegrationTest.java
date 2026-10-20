package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.service.SettlementBankTransactionFetchService;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import com.amateur.howmuchbackend.support.SettlementDepositTestCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "howmuch.settlement.notification-outbox.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("더치페이 0원 배분 요청부터 입금 완료까지 실제 HTTP 정합성")
class SettlementDutchPayZeroAmountHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private CodefConfigurationFingerprint fingerprint;
    @Autowired private ObjectMapper json;
    @MockitoBean
    private SettlementBankTransactionFetchService bankFetch;

    private final HttpClient http = HttpClient.newHttpClient();
    private Long creator;
    private Long payer;
    private Long zeroUser;
    private Long connection;
    private Long account;
    private UUID settlement;

    @ParameterizedTest
    @CsvSource({"0,10000", "4000,6000", "10000,0"})
    @DisplayName("0원 참여자의 기록과 링크를 유지하며 양수 몫만 요청·알림·입금 완료 처리한다")
    void zeroAmount_requestToCompletion(long creatorAmount, long payerAmount) throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        creator = insertUser("생성자", now);
        payer = insertUser("참여자", now);
        zeroUser = insertUser("0원 참여자", now);
        String creatorToken = login(creator, now);
        String payerToken = login(payer, now);
        String zeroToken = login(zeroUser, now);
        connection = jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,
                active,created_at,updated_at) VALUES (?, ?, ?, true, ?, ?) RETURNING id
                """, Long.class, creator, cipher.encryptConnectedId(creator, "synthetic"), fingerprint.current(), now, now);
        account = jdbc.queryForObject("""
                INSERT INTO accounts(user_id,external_connection_id,organization,candidate_key,
                external_account_key_ciphertext,type,name,masked_number,currency,product_code,active,created_at,updated_at)
                VALUES (?, ?, '0004', ?, ?, 'DEPOSIT_TRUST', '검증계좌', '****0000', 'KRW', '11', true, ?, ?) RETURNING id
                """, Long.class, creator, connection, UUID.randomUUID().toString(),
                cipher.encryptAccountKey(creator, connection, "1234567890"), now, now);
        settlement = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,
                receiving_account_id,created_at,updated_at)
                VALUES (?, ?, '0원 HTTP 검증', 10000, 'DUTCH_PAY', 'DRAFT', ?, ?, ?)
                """, settlement, creator, account, now, now);
        UUID creatorParticipant = UUID.randomUUID();
        UUID payerParticipant = UUID.randomUUID();
        UUID zeroParticipant = UUID.randomUUID();
        insertParticipant(creatorParticipant, creator, 0, now);
        insertParticipant(payerParticipant, payer, 1, now);
        insertParticipant(zeroParticipant, zeroUser, 2, now);
        String path = "/api/v2/settlements/" + settlement;
        data("POST", path + "/expenses", creatorToken, "{\"description\":\"식사\",\"amount\":10000}");
        data("PUT", path + "/allocation-type", creatorToken, "{\"allocationType\":\"DUTCH_PAY\"}");
        data("PUT", path + "/dutch-pay-allocations", creatorToken, """
                {"allocations":[{"participantId":"%s","amount":%d},
                {"participantId":"%s","amount":%d},{"participantId":"%s","amount":0}]}
                """.formatted(creatorParticipant, creatorAmount, payerParticipant, payerAmount, zeroParticipant));
        JsonNode requested = data("POST", path + "/request", creatorToken, null);
        assertThat(requested.path("status").stringValue()).isEqualTo("AWAITING_DEPOSIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_participants WHERE settlement_id=?",
                Long.class, settlement)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id=? ORDER BY amount",
                Long.class, settlement)).containsExactlyElementsOf(
                        LongStream.of(creatorAmount, payerAmount).filter(value -> value > 0)
                                .sorted().boxed().toList());
        assertThat(jdbc.queryForList("""
                SELECT recipient_user_id FROM user_notifications
                 WHERE settlement_id=? AND notification_type='SETTLEMENT_REQUEST'
                """, Long.class, settlement)).containsExactlyElementsOf(
                        payerAmount > 0 ? List.of(payer) : List.of());

        JsonNode receive = data("GET", "/api/v2/settlements?tab=RECEIVE", creatorToken, null).get(0);
        assertThat(receive.path("participantCount").asInt()).isEqualTo(3);
        int payableCount = payerAmount > 0 ? 1 : 0;
        assertThat(receive.path("payableParticipantCount").asInt()).isEqualTo(payableCount);
        assertThat(data("GET", "/api/v2/settlements?tab=PAY", zeroToken, null).size()).isZero();
        assertThat(data("GET", "/api/v2/settlements?tab=PAY", payerToken, null).size()).isEqualTo(payableCount);
        JsonNode zeroDetail = data("GET", path, zeroToken, null).path("participants").get(0);
        assertThat(zeroDetail.path("expectedAmount").isIntegralNumber()).isTrue();
        assertThat(zeroDetail.path("expectedAmount").asLong()).isZero();
        assertThat(zeroDetail.path("paymentStatus").stringValue()).isEqualTo("PAID");
        assertThat(request("GET", path + "/my-transfer-instruction", zeroToken, null, null).statusCode())
                .isEqualTo(404);

        String linkPath = URI.create(requested.path("url").stringValue()).getPath();
        String rawLink = linkPath.substring(linkPath.lastIndexOf('/') + 1);
        String publicPath = "/api/v2/settlement-links/" + rawLink;
        JsonNode linked = data("GET", publicPath, zeroToken, null);
        assertThat(linked.path("selectedParticipant").path("amount").isIntegralNumber()).isTrue();
        assertThat(linked.path("selectedParticipant").path("amount").asLong()).isZero();
        JsonNode browser = data("GET", publicPath, null, null);
        assertThat(browser.path("participantCount").asInt()).isEqualTo(3);
        assertThat(browser.path("participantCandidates").get(2).path("amount").asLong()).isZero();
        JsonNode selected = data("POST", publicPath + "/participants/" + zeroParticipant + "/select", null, null);
        assertThat(request("GET", publicPath + "/transfer-instruction", null, null,
                UUID.fromString(selected.path("participantContext").stringValue())).statusCode()).isEqualTo(404);

        // 외부 거래 조회만 대체하고 실제 입금 저장·매칭·완료 판정을 통과한다.
        var transactions = payerAmount > 0 ? List.of(new Transaction(
                null, account, UUID.randomUUID().toString().replace("-", "").repeat(2),
                AccountTransactionType.INCOME,
                BigDecimal.valueOf(payerAmount), LocalDateTime.now(ZoneOffset.UTC), "참여자",
                BigDecimal.valueOf(100_000))) : List.<Transaction>of();
        when(bankFetch.fetch(any(), eq(account), anyString(), anyString(), any(), any()))
                .thenReturn(transactions);
        data("POST", path + "/deposits/reconcile", creatorToken, null);
        JsonNode completed = data("GET", path, creatorToken, null);
        assertThat(completed.path("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(completed.path("paymentSummary").path("payableParticipantCount").asInt()).isEqualTo(payableCount);
        assertThat(completed.path("paymentSummary").path("completedParticipantCount").asInt()).isEqualTo(payableCount);
        assertThat(completed.path("paymentSummary").path("progressPercent").asInt()).isEqualTo(100);
        JsonNode completedList = data("GET", "/api/v2/settlements?tab=RECEIVE&status=COMPLETED", creatorToken, null).get(0);
        assertThat(completedList.path("completedParticipantCount").asInt()).isEqualTo(payableCount);
        assertThat(data("GET", "/api/v2/settlements?tab=PAY&status=COMPLETED", zeroToken, null).size()).isZero();
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
        assertThat(response.statusCode()).as("%s %s response: %s", method, path, response.body()).isEqualTo(200);
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
        if (zeroUser != null) sessions.deleteByUserId(zeroUser);
        // 입금 원장은 저장소의 기존 테스트 정리 규칙대로 보존한다.
        if (settlement != null && SettlementDepositTestCleanup
                .preserveSettlement(jdbc, settlement)) return;
        if (settlement != null) jdbc.update("DELETE FROM settlements WHERE id=?", settlement);
        if (account != null) {
            jdbc.update("DELETE FROM accounts WHERE id=?", account);
            jdbc.update("DELETE FROM account_records WHERE id=?", account);
        }
        if (connection != null) jdbc.update("DELETE FROM external_connections WHERE id=?", connection);
        if (payer != null) jdbc.update("DELETE FROM users WHERE id=?", payer);
        if (zeroUser != null) jdbc.update("DELETE FROM users WHERE id=?", zeroUser);
        if (creator != null) jdbc.update("DELETE FROM users WHERE id=?", creator);
    }
}
