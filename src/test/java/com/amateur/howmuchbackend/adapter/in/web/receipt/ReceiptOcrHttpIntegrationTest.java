package com.amateur.howmuchbackend.adapter.in.web.receipt;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영수증 OCR 엔드포인트를 실제 HTTP로 때린다.
 *
 * <p>standaloneSetup MockMvc 는 보안 필터도, 멀티파트 해석도 타지 않는다. 그래서 401과
 * 413처럼 컨트롤러 바깥에서 갈리는 응답은 그쪽으로는 증명할 수 없다. 여기서 증명한다.
 *
 * <p>OCR 주소는 프로퍼티로 직접 비운다. yaml 기본값도 비어 있지만, RECEIPT_OCR_BASE_URL 을
 * 내보낸 개발자 셸에서 이 테스트가 실제 홈서버 장비를 때리는 일은 없어야 한다. 그 대가로
 * 컨텍스트 캐시 항목이 하나 늘지만, 테스트가 외부 장비에 닿는 것보다는 그게 낫다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false",
        "howmuch.receipt.ocr.base-url="
})
@DisplayName("영수증 OCR 실제 HTTP 계약")
class ReceiptOcrHttpIntegrationTest {
    private static final String PATH = "/api/v2/receipts/ocr";
    private static final String BOUNDARY = "howmuch-receipt-test-boundary";
    /** 진짜 JPEG 시그니처다. 정책이 선언한 형식이 아니라 바이트를 보므로 아무 바이트나 쓸 수 없다. */
    private static final byte[] SMALL_IMAGE = jpegBytes();

    private static byte[] jpegBytes() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Long userId;
    private String accessToken;

    @BeforeEach
    void createOwnUserAndSession() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        userId = jdbc.queryForObject("""
                INSERT INTO users(identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, created_at, updated_at)
                VALUES (?, '영수증 회귀', ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, suffix, "rcp_" + suffix.substring(0, 15), "test-" + suffix.substring(0, 12), now, now);
        User user = users.findById(userId).orElseThrow();
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(userId, sessionId, "synthetic-unused-refresh",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
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
    @DisplayName("미인증 업로드는 내부 서비스에 닿기 전에 401이다")
    void receiptUpload_unauthenticated_returns401BeforeService() throws Exception {
        assertError(post(null, part("file", "receipt.jpg", "image/jpeg", SMALL_IMAGE)),
                401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("업로드 한도를 넘으면 500이 아니라 413이다")
    void receiptUpload_oversized_returnsPayloadTooLarge() throws Exception {
        // 한도는 5MB다. 문서가 413이라고 적어 둔 값을 실제로 지키는지 본다.
        byte[] tooBig = new byte[(int) (5.5 * 1024 * 1024)];

        assertError(post(accessToken, part("file", "receipt.jpg", "image/jpeg", tooBig)),
                413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("file 파트가 없으면 500이 아니라 400이다")
    void rejectsMissingFilePart() throws Exception {
        assertError(post(accessToken, part("image", "receipt.jpg", "image/jpeg", SMALL_IMAGE)),
                400, "INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("image/jpeg라고 선언한 PDF도 바이트를 보고 400으로 거절한다")
    void rejectsDisguisedPdf() throws Exception {
        // 파트의 Content-Type은 클라이언트가 정한다. 선언을 믿으면 PDF가 OCR까지 간다.
        byte[] pdf = "%PDF-1.7\nfake".getBytes(StandardCharsets.UTF_8);

        assertError(post(accessToken, part("file", "receipt.jpg", "image/jpeg", pdf)),
                400, "INVALID_RECEIPT_IMAGE");
    }

    @Test
    @DisplayName("빈 파일은 400으로 거절한다")
    void rejectsEmptyFile() throws Exception {
        assertError(post(accessToken, part("file", "receipt.jpg", "image/jpeg", new byte[0])),
                400, "INVALID_RECEIPT_IMAGE");
    }

    @Test
    @DisplayName("OCR 주소가 비어 있으면 503으로 재시도를 알린다")
    void answersUnavailableWhenIntegrationIsOff() throws Exception {
        assertError(post(accessToken, part("file", "receipt.jpg", "image/jpeg", SMALL_IMAGE)),
                503, "RECEIPT_OCR_UNAVAILABLE");
    }

    private byte[] part(String name, String fileName, String contentType, byte[] content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        body.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private HttpResponse<String> post(String token, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
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
