package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementListHttpIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private Long userId;

    @Test
    void authenticatesAndPaginatesThroughRealHttp() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        userId = jdbc.queryForObject("""
                INSERT INTO users(identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                                  role, active, created_at, updated_at)
                VALUES (?, '페이지 HTTP', ?, ?, 'not-used', true, 'USER', true, ?, ?) RETURNING id
                """, Long.class, "pagination-http-" + suffix, "pg_" + suffix.substring(0, 15),
                "test-" + suffix.substring(0, 12), now, now);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO settlements(id, creator_id, title, total_amount, status, created_at, updated_at)
                    VALUES (?, ?, '페이지 HTTP', 600, 'REQUESTED', ?, ?)
                    """, UUID.randomUUID(), userId, now, now);
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(userId, sessionId, "synthetic", now.plusMinutes(10)));
        String token = tokens.createAccessToken(users.findById(userId).orElseThrow(), sessionId);
        JsonNode first = page(request("?size=2", token));
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        JsonNode last = page(request("?size=2&cursor=" + first.path("nextCursor").stringValue(), token));
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
        String lastId = last.path("items").get(0).path("settlementId").stringValue();
        first.path("items").forEach(item -> assertThat(item.path("settlementId").stringValue()).isNotEqualTo(lastId));
        JsonNode empty = page(request("?tab=PAY", token));
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        assertThat(empty.path("hasNext").asBoolean()).isFalse();
        assertThat(request("", null).statusCode()).isEqualTo(401);
        HttpResponse<String> invalidSize = request("?size=51", token);
        assertThat(invalidSize.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalidSize.body()).path("error").path("code").stringValue()).isEqualTo("INVALID_ARGUMENT");
        HttpResponse<String> invalidCursor = request("?cursor=invalid", token);
        assertThat(invalidCursor.statusCode()).isEqualTo(400);
        assertThat(json.readTree(invalidCursor.body()).path("error").path("code").stringValue())
                .isEqualTo("INVALID_SETTLEMENT_CURSOR");
    }

    private JsonNode page(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private HttpResponse<String> request(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/settlements" + query));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterEach
    void cleanUp() {
        if (userId != null) {
            sessions.deleteByUserId(userId);
            jdbc.update("DELETE FROM settlements WHERE creator_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
