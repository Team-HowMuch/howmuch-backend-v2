package com.amateur.howmuchbackend.infra.security;

import com.amateur.howmuchbackend.adapter.out.security.jwt.JwtTokenProvider;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.security.config.JwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@DisplayName("실제 HTTP 보안 필터 체인 인증·인가 회귀")
class SecurityFilterChainIntegrationTest {
    private static final String PROFILE = "/api/v2/user/me";
    private static final String ADMIN = "/api/v2/admin/common-codes/groups";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Long userId;
    private User user;
    private String sessionId;
    private String accessToken;

    @BeforeEach
    void createOwnUserAndRealRedisSession() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        userId = jdbc.queryForObject("""
                INSERT INTO users(identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, created_at, updated_at)
                VALUES (?, '보안 회귀', ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, suffix, "sec_" + suffix.substring(0, 15), "test-" + suffix.substring(0, 12), now, now);
        user = users.findById(userId).orElseThrow();
        sessionId = UUID.randomUUID().toString();
        saveSession(sessionId);
        accessToken = tokens.createAccessToken(user, sessionId);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        if (userId != null) {
            sessions.deleteByUserId(userId);
            jdbc.update("DELETE FROM auth_audit_logs WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    @Test
    @DisplayName("미인증 요청은 보호 API에서 401 JSON을 받는다")
    void protectedApi_unauthenticated_returns401Json() throws Exception {
        assertError(request("GET", PROFILE, null), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("유효한 Access 토큰과 활성 Redis 세션은 본인 프로필을 조회한다")
    void protectedApi_activeAccessTokenAndSession_returnsOwnProfile() throws Exception {
        var response = request("GET", PROFILE, accessToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("userId").longValue()).isEqualTo(userId);
        // 뒤따르는 익명 요청이 앞선 인증 요청의 사용자 정보를 물려받지 않는지 확인한다.
        assertError(request("GET", PROFILE, null), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("Redis 세션이 활성 상태여도 만료된 JWT는 401이다")
    void protectedApi_expiredAccessToken_rejectedDespiteActiveSession() throws Exception {
        var expiredIssuer = new JwtTokenProvider(new JwtProperties(jwtProperties.secret(), -60, 600));
        assertError(request("GET", PROFILE, expiredIssuer.createAccessToken(user, sessionId)), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("다른 키로 서명한 JWT는 401이다")
    void protectedApi_invalidSignature_rejected() throws Exception {
        var otherIssuer = new JwtTokenProvider(new JwtProperties("other-test-only-signing-key-never-for-production-307", 600, 600));
        assertError(request("GET", PROFILE, otherIssuer.createAccessToken(user, sessionId)), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("잘못된 형식의 토큰은 401이다")
    void protectedApi_malformedToken_rejected() throws Exception {
        assertError(request("GET", PROFILE, "not-a-jwt"), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("활성 세션의 Refresh 토큰으로 보호 API에 접근할 수 없다")
    void protectedApi_refreshTokenAsAccessToken_rejected() throws Exception {
        assertError(request("GET", PROFILE, tokens.createRefreshToken(user, sessionId)), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("실제 로그아웃 이후 서명이 유효한 기존 Access 토큰도 401이다")
    void protectedApi_loggedOutAccessToken_revoked() throws Exception {
        assertThat(request("GET", PROFILE, accessToken).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/v2/auth/logout", accessToken).statusCode()).isEqualTo(200);
        assertThat(sessions.findByUserId(userId)).isEmpty();
        assertError(request("GET", PROFILE, accessToken), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("세션 교체 시 이전 토큰은 401이고 현재 세션 토큰은 200이다")
    void protectedApi_replacedSession_oldRejectedCurrentAccepted() throws Exception {
        String replacement = UUID.randomUUID().toString();
        saveSession(replacement);
        assertError(request("GET", PROFILE, accessToken), 401, "UNAUTHORIZED");
        assertThat(request("GET", PROFILE, tokens.createAccessToken(user, replacement)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("관리자 API는 미인증 401과 일반 사용자 403을 구분한다")
    void adminApi_missingPermission_unauthenticated401AndUser403() throws Exception {
        assertError(request("GET", ADMIN, null), 401, "UNAUTHORIZED");
        assertError(request("GET", ADMIN, accessToken), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("관리자 API는 DB의 ADMIN 권한을 허용하고 권한 회수 후 403을 반환한다")
    void adminApi_databaseAdminRole_allowedThenRevocationForbidden() throws Exception {
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", userId);
        assertThat(request("GET", ADMIN, accessToken).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE users SET role='USER' WHERE id=?", userId);
        assertError(request("GET", ADMIN, accessToken), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("유효한 API 인증으로도 서비스 포트의 관리 지표에 접근할 수 없다")
    void managementMetrics_validApiToken_servicePortAccessRejected() throws Exception {
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", userId);
        assertThat(request("GET", "/actuator/metrics", accessToken).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/actuator/prometheus", accessToken).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("공개 관리자 화면은 인증 없이 접근 가능하다")
    void preservesPublicPage() throws Exception {
        assertThat(request("GET", "/admin/common-codes", null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "not-a-uuid"})
    @DisplayName("실제 공개 API에서 필수 헤더 누락·빈 값·형식 오류는 400이다")
    void rejectsInvalidRequiredHeaderOverHttp(String value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v2/settlement-links/" + "x".repeat(43) + "/transfer-instruction"))
                .timeout(Duration.ofSeconds(10));
        if (!value.equals("missing")) {
            request.header("Link-Participant-Context", value);
        }
        assertInvalidArgument(http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?accountId=", "?accountId=not-a-number"})
    @DisplayName("실제 JWT·Redis 인증을 통과한 요청의 필수 파라미터 오류는 400이다")
    void rejectsInvalidRequiredParameterOverHttp(String query) throws Exception {
        assertInvalidArgument(request("GET", paymentCandidatesPath() + query, accessToken));
    }

    @Test
    @DisplayName("필수 파라미터가 누락되어도 미인증 보호 API는 먼저 401로 거절한다")
    void preservesAuthenticationBeforeParameterBinding() throws Exception {
        assertError(request("GET", paymentCandidatesPath(), null), 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}"})
    @DisplayName("실제 로그인 API의 본문 누락·파싱·검증 오류는 기존 400을 유지한다")
    void preservesBodyErrorsOverHttp(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertInvalidArgument(response);
        if (body.equals("{}")) {
            var details = json.readTree(response.body()).path("error").path("details");
            assertThat(details.path("phoneNumber").stringValue()).isEqualTo("휴대폰 번호는 필수입니다.");
            assertThat(details.path("pin").stringValue()).isEqualTo("PIN은 필수입니다.");
        }
    }

    private String paymentCandidatesPath() {
        return "/api/v2/settlements/" + UUID.randomUUID() + "/expense-candidates/payment-history";
    }

    private void assertInvalidArgument(HttpResponse<String> response) {
        assertError(response, 400, "INVALID_ARGUMENT");
        var body = json.readTree(response.body());
        assertThat(body.has("data")).isFalse();
        assertThat(body.path("error").path("message").stringValue()).isEqualTo("요청값이 올바르지 않습니다.");
        assertThat(body.path("error").path("timestamp").stringValue()).isNotBlank();
    }

    private void saveSession(String id) {
        sessions.save(new AuthSession(userId, id, "synthetic-unused-refresh", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json.readTree(response.body());
        assertThat(body.path("success").booleanValue()).isFalse();
        assertThat(body.path("error").path("status").intValue()).isEqualTo(status);
        assertThat(body.path("error").path("code").stringValue()).isEqualTo(code);
    }
}
