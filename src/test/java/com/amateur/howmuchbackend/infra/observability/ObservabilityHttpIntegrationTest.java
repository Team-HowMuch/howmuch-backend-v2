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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@ActiveProfiles({"local", "observability-test"})
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
    void observability_securityRejection_correlatedAndCounted(CapturedOutput output) throws Exception {
        double before = count("user", "401");
        var response = get(port, "/api/v2/user/me");
        assertThat(response.statusCode()).isEqualTo(401);
        assertCorrelated(response, output);
        assertThat(count("user", "401")).isEqualTo(before + 1);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void observability_settlementFailure_safeJsonAndCounterPreserved(CapturedOutput output) throws Exception {
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
        assertThat(metrics.body()).contains("area", "status").doesNotContain(SECRET, "requestId", "traceId");
        var prometheus = get(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("howmuch_http_requests_total").doesNotContain(SECRET, "http_server_requests");
        assertThat(get(port, "/actuator/metrics").statusCode()).isBetween(400, 499);
        assertThat(get(port, "/actuator/prometheus").statusCode()).isBetween(400, 499);
        assertThat(get(managementPort, "/actuator/metrics/http.server.requests").statusCode()).isBetween(400, 499);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isBetween(400, 499);
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

    @Test
    void doesNotPropagateW3cHeadersToExternalRequests() throws Exception {
        var receivedHeaders = new AtomicReference<String>();
        try (var server = new ServerSocket(0)) {
            Thread serverThread = Thread.startVirtualThread(() -> acceptOnce(server, receivedHeaders));
            String target = "http://127.0.0.1:" + server.getLocalPort() + "/provider";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/v2/settlement-links/_observability/outgoing?url="
                            + java.net.URLEncoder.encode(target, StandardCharsets.UTF_8)))
                    .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                    .header("tracestate", "sensitive=" + SECRET)
                    .header("baggage", "secret=" + SECRET)
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            serverThread.join(Duration.ofSeconds(3));
            assertThat(receivedHeaders).hasValueSatisfying(headers -> assertThat(headers)
                    .doesNotContainIgnoringCase("traceparent", "tracestate", "baggage"));
        }
    }

    private void acceptOnce(ServerSocket server, AtomicReference<String> receivedHeaders) {
        try (var socket = server.accept();
             var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream output = socket.getOutputStream()) {
            StringBuilder headers = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append('\n');
            }
            receivedHeaders.set(headers.toString());
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception exception) {
            receivedHeaders.set("server-error: " + exception.getClass().getSimpleName());
        }
    }

    private HttpResponse<String> get(int targetPort, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path + "?otp=" + SECRET))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + SECRET)
                .header("X-Request-ID", SECRET)
                .header("X-Trace-ID", SECRET)
                .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                .header("tracestate", "sensitive=" + SECRET)
                .header("baggage", "secret=" + SECRET)
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double count(String area, String status) {
        var counter = registry.find("howmuch.http.requests").tags("area", area, "status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertCorrelated(HttpResponse<String> response, CapturedOutput output) throws Exception {
        String id = response.headers().firstValue("X-Trace-ID").orElseThrow();
        assertThat(id).matches("[0-9a-f]{32}").isNotEqualTo("11111111111111111111111111111111");
        assertThat(response.headers().firstValue("X-Request-ID")).isEmpty();
        // The response can arrive just before the outer filter's finally block completes.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!output.getAll().contains(id) && System.nanoTime() < deadline) Thread.sleep(10);
        var matching = output.getAll().lines().filter(line -> line.contains(id)).toList();
        assertThat(matching).isNotEmpty();
        assertThat(matching).allSatisfy(line -> {
            var event = json.readTree(line);
            assertThat(event.path("traceId").stringValue()).isEqualTo(id);
            assertThat(event.path("spanId").stringValue()).matches("[0-9a-f]{16}");
            assertThat(event.path("event").stringValue()).isIn("request_failed", "request_exception");
        });
    }

    @RestController
    static class FailureProbe {
        private final RestClient.Builder restClientBuilder;

        FailureProbe(RestClient.Builder restClientBuilder) {
            this.restClientBuilder = restClientBuilder;
        }

        @GetMapping("/api/v2/settlement-links/_observability/{value}")
        void fail(@PathVariable("value") String value) {
            if (value.equals("auth")) {
                throw ApplicationException.of(AuthStatusCode.INVALID_LOGIN_CREDENTIALS, new IllegalStateException(SECRET));
            }
            var failure = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
            failure.addSuppressed(new IllegalStateException(SECRET));
            throw failure;
        }

        @GetMapping("/api/v2/settlement-links/_observability/outgoing")
        void outgoing(@RequestParam String url) {
            restClientBuilder.build().get().uri(url).retrieve().toBodilessEntity();
        }
    }
}
