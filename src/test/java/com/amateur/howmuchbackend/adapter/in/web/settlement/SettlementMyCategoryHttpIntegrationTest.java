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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
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

/** 같은 정산을 생성자와 참여자가 실제 HTTP로 조회할 때 각자의 내 카테고리가 보이는지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@DisplayName("정산 목록·상세의 내 카테고리 HTTP")
class SettlementMyCategoryHttpIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private SettlementUnpaidFixtures fixtures;
    private Long creatorId;
    private Long memberId;
    private Long otherMemberId;

    @BeforeEach
    void setUp() {
        fixtures = new SettlementUnpaidFixtures(jdbc);
        creatorId = fixtures.user();
        memberId = fixtures.user();
        otherMemberId = fixtures.user();
    }

    @AfterEach
    void tearDown() {
        sessions.deleteByUserId(creatorId);
        sessions.deleteByUserId(memberId);
        sessions.deleteByUserId(otherMemberId);
        fixtures.close();
    }

    @Test
    @DisplayName("바꾸지 않은 회원은 자동 분류 값을, 바꾼 참여자는 자기 값을 보고, 분류 전 정산은 null이다")
    void myCategory_perViewer() throws Exception {
        UUID classified = fixtures.settlement(creatorId, SettlementStatus.AWAITING_DEPOSIT,
                SettlementAllocationType.DUTCH_PAY);
        fixtures.participant(classified, creatorId);
        fixtures.share(classified, fixtures.participant(classified, memberId), 10_000);
        fixtures.share(classified, fixtures.participant(classified, otherMemberId), 10_000);
        jdbc.update("""
                INSERT INTO settlement_categories (settlement_id, status, category_code, source, classified_at,
                    created_at, updated_at)
                VALUES (?, 'CLASSIFIED', 'CAFE_SNACK', 'RULE', ?, ?, ?)
                """, classified, NOW, NOW, NOW);
        jdbc.update("""
                INSERT INTO settlement_user_categories (settlement_id, user_id, category_code, created_at, updated_at)
                VALUES (?, ?, 'FOOD', ?, ?)
                """, classified, memberId, NOW, NOW);
        UUID unclassified = fixtures.settlement(creatorId, SettlementStatus.AWAITING_DEPOSIT,
                SettlementAllocationType.DUTCH_PAY);
        fixtures.participant(unclassified, creatorId);
        String creatorToken = accessToken(creatorId);
        String memberToken = accessToken(memberId);
        String otherMemberToken = accessToken(otherMemberId);

        JsonNode creatorList = data(get("/api/v2/settlements?tab=RECEIVE", creatorToken));
        assertThat(item(creatorList, classified).path("myCategory").path("code").stringValue())
                .isEqualTo("CAFE_SNACK");
        assertThat(item(creatorList, classified).path("myCategory").path("icon").stringValue())
                .isEqualTo("cafe-snack");
        assertThat(item(creatorList, unclassified).has("myCategory")).isTrue();
        assertThat(item(creatorList, unclassified).path("myCategory").isNull()).isTrue();
        assertThat(data(get("/api/v2/settlements/" + classified, otherMemberToken))
                .path("myCategory").path("code").stringValue()).isEqualTo("CAFE_SNACK");

        JsonNode memberList = data(get("/api/v2/settlements?tab=PAY", memberToken));
        assertThat(item(memberList, classified).path("myCategory").path("code").stringValue()).isEqualTo("FOOD");
        assertThat(data(get("/api/v2/settlements/" + classified, memberToken))
                .path("myCategory").path("code").stringValue()).isEqualTo("FOOD");
    }

    private JsonNode item(JsonNode list, UUID settlementId) {
        for (JsonNode item : list) {
            if (settlementId.toString().equals(item.path("settlementId").stringValue())) {
                return item;
            }
        }
        throw new AssertionError("settlement " + settlementId + " not in " + list);
    }

    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private String accessToken(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(
                userId, sessionId, "synthetic-unused-refresh", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
        return tokens.createAccessToken(users.findById(userId).orElseThrow(), sessionId);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
