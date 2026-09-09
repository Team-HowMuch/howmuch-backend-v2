package com.amateur.howmuchbackend.infra.observability;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@Import(ObservabilityHttpIntegrationTest.FailureProbe.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityHttpIntegrationTest {
    private static final String SECRET = "synthetic-sensitive-pin-otp-token-rrn-311";
    @LocalServerPort private int port;
    @Value("${local.management.port}") private int managementPort;
    @Autowired private MeterRegistry registry;
    @Autowired private ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void realSecurityRejectionIsCorrelatedAndCounted(CapturedOutput output) throws Exception {
        double before = count("other", "401");
        var response = get(port, "/api/v2/user/me");
        assertThat(response.statusCode()).isEqualTo(401);
        assertCorrelated(response, output);
        assertThat(count("other", "401")).isEqualTo(before + 1);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void handledSettlementFailureKeepsSafeJsonAndCounter(CapturedOutput output) throws Exception {
        double before = count("settlement", "500");
        var response = get(port, "/api/v2/settlement-links/_observability/" + SECRET);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("INTERNAL_SERVER_ERROR").doesNotContain(SECRET);
        assertCorrelated(response, output);
        assertThat(output.getAll()).contains("request_exception", "IllegalStateException").doesNotContain(SECRET);
        assertThat(count("settlement", "500")).isEqualTo(before + 1);
    }

    @Test
    void authenticationApplicationFailureAndMalformedBodyDoNotLeak(CapturedOutput output) throws Exception {
        var response = get(port, "/api/v2/settlement-links/_observability/auth");
        assertThat(response.statusCode()).isEqualTo(401);
        assertCorrelated(response, output);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"" + SECRET)).build();
        double before = count("auth", "400");
        var malformed = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertCorrelated(malformed, output);
        assertThat(count("auth", "400")).isEqualTo(before + 1);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void metricsAreAvailableOnlyThroughSeparateLoopbackManagementPort() throws Exception {
        get(port, "/api/v2/user/me");
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
        var metrics = get(managementPort, "/actuator/metrics/howmuch.http.requests");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("area", "status").doesNotContain(SECRET, "requestId");
        var prometheus = get(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("howmuch_http_requests_total").doesNotContain(SECRET, "http_server_requests");
        assertThat(get(port, "/actuator/metrics").statusCode()).isBetween(400, 499);
        assertThat(get(port, "/actuator/prometheus").statusCode()).isBetween(400, 499);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isBetween(400, 499);
        assertThat(registry.find("http.server.requests").meters()).isEmpty();
    }

    @Autowired private ApplicationContext applicationContext;

    @Test
    void readinessReflectsAvailabilityAndRemainsPrivate() throws Exception {
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isBetween(400, 499);
        try {
            AvailabilityChangeEvent.publish(applicationContext,
                    ReadinessState.REFUSING_TRAFFIC);
            var response = get(managementPort, "/actuator/health/readiness");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("components", "details");
        } finally {
            AvailabilityChangeEvent.publish(applicationContext,
                    ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    private HttpResponse<String> get(int targetPort, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path + "?otp=" + SECRET))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + SECRET)
                .header("X-Request-ID", SECRET).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double count(String area, String status) {
        var counter = registry.find("howmuch.http.requests").tags("area", area, "status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertCorrelated(HttpResponse<String> response, CapturedOutput output) throws Exception {
        String id = response.headers().firstValue("X-Request-ID").orElseThrow();
        UUID.fromString(id);
        // The response can arrive just before the outer filter's finally block completes.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!output.getAll().contains(id) && System.nanoTime() < deadline) Thread.sleep(10);
        var matching = output.getAll().lines().filter(line -> line.contains(id)).toList();
        assertThat(matching).isNotEmpty();
        assertThat(matching).allSatisfy(line -> {
            var event = json.readTree(line);
            assertThat(event.path("requestId").stringValue()).isEqualTo(id);
            assertThat(event.path("event").stringValue()).isIn("request_failed", "request_exception");
        });
    }

    @RestController
    static class FailureProbe {
        @GetMapping("/api/v2/settlement-links/_observability/{value}")
        void fail(@PathVariable("value") String value) {
            if (value.equals("auth")) {
                throw ApplicationException.of(AuthStatusCode.INVALID_LOGIN_CREDENTIALS, new IllegalStateException(SECRET));
            }
            var failure = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
            failure.addSuppressed(new IllegalStateException(SECRET));
            throw failure;
        }
    }
}
