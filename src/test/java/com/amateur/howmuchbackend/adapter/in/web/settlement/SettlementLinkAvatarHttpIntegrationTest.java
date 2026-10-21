package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@DisplayName("공개 정산 링크 아바타 실제 HTTP·보안·PostgreSQL 회귀")
class SettlementLinkAvatarHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementLinkTokenPort tokens;
    @Autowired private ObjectMapper json;
    @MockitoBean private ProfileImageStoragePort images;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final UUID settlementId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID guestId = UUID.randomUUID();
    private final String fileName = UUID.randomUUID() + ".png";
    private Long userId;
    private String token;

    @BeforeEach
    void createOwnLinkAndMixedParticipants() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var suffix = UUID.randomUUID().toString().replace("-", "");
        userId = jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,
                    phone_verified,role,active,avatar_file_name,created_at,updated_at)
                VALUES (?,'회원',?,?,'unused',true,'USER',true,?,?,?) RETURNING id
                """, Long.class, suffix, "ava_" + suffix.substring(0, 12), "test-" + suffix.substring(0, 12), fileName, now, now);
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,version,created_at,updated_at)
                VALUES (?,?,'사진 테스트',10000,'ITEM_ALLOCATION','REQUESTED',0,?,?)
                """, settlementId, userId, now, now);
        jdbc.update("""
                INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,display_name,display_order,created_at,updated_at)
                VALUES (?,?,'REGISTERED_USER',?,'회원',0,?,?), (?,?,'CONTACT',NULL,'비회원',1,?,?)
                """, memberId, settlementId, userId, now, now, guestId, settlementId, now, now);
        var issued = tokens.issue(now);
        token = issued.rawToken();
        jdbc.update("""
                INSERT INTO settlement_links(id,settlement_id,token_hash,status,expires_at,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID(), settlementId, issued.tokenHash(), issued.expiresAt(), now.minusMinutes(10), now);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM settlements WHERE id=?", settlementId);
        if (userId != null) jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    @Test
    @DisplayName("비로그인 후보 URL을 실제로 열고 비회원·탈퇴·완료·폐기·만료 경계를 차단한다")
    void anonymousFlow_avatarReadableOnlyWithUsableLinkAndActiveMember() throws Exception {
        var body = get(linkPath());
        assertThat(body.statusCode()).isEqualTo(200);
        var candidates = json.readTree(body.body()).path("data").path("participantCandidates");
        String avatarUrl = candidates.get(0).path("avatarUrl").stringValue();
        assertThat(avatarUrl).startsWith(linkPath() + "/participants/" + memberId + "/avatar?v=");
        assertThat(candidates.get(1).path("avatarUrl").isNull()).isTrue();
        byte[] content = {1, 2, 3};
        when(images.load(fileName)).thenReturn(Optional.of(new ProfileImage("image/png", content)));

        var image = get(avatarUrl);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(content);
        assertThat(image.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(image.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(get(linkPath() + "/participants/" + guestId + "/avatar").statusCode()).isEqualTo(404);
        assertThat(get(linkPath() + "/participants/" + UUID.randomUUID() + "/avatar").statusCode()).isEqualTo(404);

        jdbc.update("UPDATE users SET active=false WHERE id=?", userId);
        assertThat(json.readTree(get(linkPath()).body()).path("data").path("participantCandidates").get(0)
                .path("avatarUrl").isNull()).isTrue();
        assertThat(get(avatarUrl).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE users SET active=true WHERE id=?", userId);
        jdbc.update("UPDATE settlements SET status='COMPLETED' WHERE id=?", settlementId);
        assertThat(get(avatarUrl).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE settlements SET status='REQUESTED' WHERE id=?", settlementId);
        jdbc.update("UPDATE settlement_links SET status='REVOKED' WHERE settlement_id=?", settlementId);
        assertThat(get(avatarUrl).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE settlement_links SET status='ACTIVE',expires_at=? WHERE settlement_id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), settlementId);
        assertThat(get(avatarUrl).statusCode()).isEqualTo(404);
    }

    private String linkPath() {
        return "/api/v2/settlement-links/" + token;
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
