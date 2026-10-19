package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadGroupNettingDebtPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local, disposable infrastructure only. No absolute timing assertions. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {
            "howmuch.settlement.netting.enabled=true",
            "howmuch.settlement.notification-outbox.enabled=false",
            "howmuch.settlement.netting.dispatch-delay-ms=3600000",
            "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@EnabledIfEnvironmentVariable(named = "NETTING_HTTP_PERF_QA", matches = "true")
class GroupNettingHttpPerformanceTest extends GroupNettingDirectIntegrationTest {
    @LocalServerPort int port;
    @Autowired AuthSessionPort authSessions;
    @Autowired AuthTokenPort tokens;
    @Autowired LoadUserPort users;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LoadGroupNettingDebtPort sourceReads;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Map<String, Object>> evidence = new ArrayList<>();

    record Response(int status, double ms, JsonNode data) {}

    String token(long user) {
        String session = UUID.randomUUID().toString();
        authSessions.save(
                new AuthSession(
                        user,
                        session,
                        "test-unused",
                        LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
        return tokens.createAccessToken(users.findById(user).orElseThrow(), session);
    }

    Response call(String method, String path, String token, UUID key, int expected)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key.toString());
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        long start = System.nanoTime();
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        assertThat(result.statusCode())
                .as("%s %s: %s", method, path, result.body())
                .isEqualTo(expected);
        return new Response(result.statusCode(), ms, json.readTree(result.body()).get("data"));
    }

    Map<String, Object> distribution(String scenario, List<Double> samples) {
        var sorted = samples.stream().sorted().toList();
        return Map.of(
                "scenario",
                scenario,
                "requests",
                sorted.size(),
                "medianMs",
                sorted.get(sorted.size() / 2),
                "p95Ms",
                sorted.get((int) Math.ceil(sorted.size() * .95) - 1),
                "maxMs",
                sorted.getLast());
    }

    List<Double> reads(String path, String token, int count) throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<Double>>();
            for (int i = 0; i < count; i++)
                tasks.add(pool.submit(() -> call("GET", path, token, null, 200).ms()));
            var times = new ArrayList<Double>();
            for (var task : tasks) times.add(task.get(30, TimeUnit.SECONDS));
            return times;
        }
    }

    @Test
    void httpScenarioWithSlowBankAndScale() throws Exception {
        for (int size : List.of(2, 20, 100)) {
            var f = fixture(15000, 10000);
            for (int i = 2; i < size; i++)
                original(
                        f.group(),
                        f.b(),
                        f.a(),
                        f.accountB(),
                        1000L,
                        LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
            as(f.a());
            String access = token(f.a());
            String root = "/api/v2/groups/" + f.group();
            var estimate = call("GET", root + "/settlement-nettings/estimate", access, null, 200);
            evidence.add(
                    Map.of(
                            "scenario",
                            "estimate cold",
                            "originals",
                            size,
                            "ms",
                            estimate.ms(),
                            "data",
                            estimate.data()));
            assertThat(estimate.data().get("eligibleSettlementCount").intValue()).isEqualTo(size);
            evidence.add(
                    distribution(
                            "estimate warm originals=" + size,
                            reads(root + "/settlement-nettings/estimate", access, 20)));
            evidence.add(
                    distribution(
                            "unrelated GET before originals=" + size,
                            reads("/api/v2/notifications/unread-count", access, 20)));
            var bankCalls = new AtomicInteger();
            var bankStarted = new CountDownLatch(1);
            var bankActive = new AtomicInteger();
            var bankPeak = new AtomicInteger();
            when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                    .thenAnswer(
                            inv -> {
                                assertThat(
                                                org.springframework.transaction.support
                                                        .TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                bankCalls.incrementAndGet();
                                bankStarted.countDown();
                                bankPeak.accumulateAndGet(bankActive.incrementAndGet(), Math::max);
                                try {
                                    Thread.sleep(1000);
                                    return List.of();
                                } finally {
                                    bankActive.decrementAndGet();
                                }
                            });
            clearInvocations(sourceReads);
            var key = UUID.randomUUID();
            long start = System.nanoTime();
            var queued = call("POST", root + "/settlement-nettings", access, key, 202);
            String execution = queued.data().get("executionId").stringValue();
            var repeat = call("POST", root + "/settlement-nettings", access, key, 202);
            assertThat(repeat.data().get("executionId").stringValue()).isEqualTo(execution);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var job = pool.submit(() -> dispatcher.dispatch());
                assertThat(bankStarted.await(10, TimeUnit.SECONDS)).isTrue();
                evidence.add(
                        distribution(
                                "unrelated GET during slow bank originals=" + size,
                                reads("/api/v2/notifications/unread-count", access, 20)));
                evidence.add(
                        distribution(
                                "execution polling during slow bank originals=" + size,
                                reads(
                                        root + "/settlement-netting-executions/" + execution,
                                        access,
                                        20)));
                job.get(40, TimeUnit.SECONDS);
            }
            var completed =
                    call(
                            "GET",
                            root + "/settlement-netting-executions/" + execution,
                            access,
                            null,
                            200);
            assertThat(completed.data().get("status").stringValue())
                    .as("%s", completed.data())
                    .isEqualTo("SUCCEEDED");
            String id = completed.data().get("nettingId").stringValue();
            var result = call("GET", root + "/settlement-nettings/" + id, access, null, 200);
            var n = nettings.get(f.group(), UUID.fromString(id));
            assertThat(n.transfers()).hasSize(1);
            assertThat(n.transfers().getFirst().amount()).isEqualTo(5000L + (size - 2) * 1000L);
            assertThat(n.sources()).hasSize(size);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM group_netting_executions WHERE"
                                        + " group_id=?",
                                    Integer.class,
                                    f.group()))
                    .isOne();
            assertThat(bankCalls).hasValue(2);
            assertThat(bankPeak).hasValue(2);
            long fullReads =
                    mockingDetails(sourceReads).getInvocations().stream()
                            .filter(
                                    invocation ->
                                            invocation
                                                    .getMethod()
                                                    .getName()
                                                    .equals("findAwaitingSources"))
                            .count();
            assertThat(fullReads)
                    .as("Whole-group reads must not grow with %s originals", size)
                    .isLessThanOrEqualTo(6);
            evidence.add(
                    Map.of(
                            "scenario",
                            "target identity reads",
                            "originals",
                            size,
                            "calls",
                            mockingDetails(sourceReads).getInvocations().stream()
                                    .filter(
                                            invocation ->
                                                    invocation
                                                            .getMethod()
                                                            .getName()
                                                            .equals("findAwaitingSourceIdentity"))
                                    .count()));
            evidence.add(
                    Map.of(
                            "scenario",
                            "compression",
                            "originals",
                            size,
                            "enqueueMs",
                            queued.ms(),
                            "elapsedMs",
                            (System.nanoTime() - start) / 1_000_000.0,
                            "bankCalls",
                            bankCalls.get(),
                            "netTransfers",
                            n.transfers().size(),
                            "sources",
                            n.sources().size(),
                            "detailMs",
                            result.ms(),
                            "maxConcurrentBankCalls",
                            bankPeak.get(),
                            "fullSourceReadCalls",
                            mockingDetails(sourceReads).getInvocations().stream()
                                    .filter(
                                            i ->
                                                    i.getMethod()
                                                            .getName()
                                                            .equals("findAwaitingSources"))
                                    .count()));
            evidence.add(
                    Map.of(
                            "scenario",
                            "batch steps",
                            "originals",
                            size,
                            "steps",
                            jdbc.queryForList(
                                    "SELECT e.step_name,extract(epoch FROM"
                                        + " (e.end_time-e.start_time))*1000 AS duration_ms FROM"
                                        + " batch_step_execution e JOIN batch_job_execution_params"
                                        + " p ON p.job_execution_id=e.job_execution_id WHERE"
                                        + " p.parameter_name='executionId' AND p.parameter_value=?"
                                        + " ORDER BY e.step_execution_id",
                                    execution)));
            // Confirm the netted payment through HTTP after recording a synthetic bank transaction.
            var transfer = n.transfers().getFirst();
            income(f.accountB(), transfer.amount(), transfer.reference());
            when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(List.of());
            as(f.b());
            String receiver = token(f.b());
            var reconciliation =
                    call(
                            "POST",
                            root + "/settlement-nettings/" + id + "/deposits/reconcile",
                            receiver,
                            UUID.randomUUID(),
                            202);
            dispatcher.dispatch();
            var reconciled =
                    call(
                            "GET",
                            root
                                    + "/settlement-netting-executions/"
                                    + reconciliation.data().get("executionId").stringValue(),
                            receiver,
                            null,
                            200);
            assertThat(reconciled.data().get("status").stringValue())
                    .as("%s", reconciled.data())
                    .isEqualTo("SUCCEEDED");
            assertThat(nettings.get(f.group(), UUID.fromString(id)).status())
                    .isEqualTo("COMPLETED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM settlements WHERE id IN (SELECT"
                                        + " settlement_id FROM group_netting_sources WHERE"
                                        + " netting_id=?) AND status<>'COMPLETED'",
                                    Integer.class,
                                    UUID.fromString(id)))
                    .isZero();
            evidence.add(
                    Map.of(
                            "scenario",
                            "reconcile completes all sources",
                            "originals",
                            size,
                            "status",
                            "COMPLETED",
                            "confirmedAmount",
                            transfer.amount()));
            authSessions.deleteByUserId(f.a());
            authSessions.deleteByUserId(f.b());
        }
        Path file =
                Path.of(
                        System.getenv()
                                .getOrDefault(
                                        "NETTING_HTTP_PERF_OUTPUT",
                                        "build/reports/netting-http-performance.json"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("NETTING_HTTP_QA " + json.writeValueAsString(evidence));
    }
}
