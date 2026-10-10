package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Aggregate operational measurements only; notification content and identifiers are never metric labels. */
@Component
public class NotificationQueueMetricsBinder implements MeterBinder {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(0, Map.of());

    public NotificationQueueMetricsBinder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String state : new String[]{"pending", "retry", "processing", "failed"}) {
            Gauge.builder("howmuch.notification.delivery.rows", this, binder -> binder.value(state))
                    .tag("state", state).register(registry);
        }
        Gauge.builder("howmuch.notification.delivery.oldest.seconds", this, binder -> binder.value("oldest"))
                .register(registry);
        Gauge.builder("howmuch.notification.publication.expired.leases", this, binder -> binder.value("expired"))
                .register(registry);
    }

    private double value(String field) {
        long now = System.nanoTime();
        if (now - snapshot.loadedAt() >= TimeUnit.SECONDS.toNanos(10) || snapshot.loadedAt() == 0) refresh(now);
        return snapshot.values().getOrDefault(field, 0.0);
    }

    private synchronized void refresh(long now) {
        if (snapshot.loadedAt() != 0 && now - snapshot.loadedAt() < TimeUnit.SECONDS.toNanos(10)) return;
        try {
            Map<String, Double> values = jdbc.queryForObject("""
                    SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                           COUNT(*) FILTER (WHERE status = 'RETRY') AS retry,
                           COUNT(*) FILTER (WHERE status = 'PROCESSING') AS processing,
                           COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                           COALESCE(MAX(GREATEST(0, EXTRACT(EPOCH FROM
                               ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - created_at))))
                               FILTER (WHERE status IN ('PENDING', 'RETRY')), 0) AS oldest,
                           COUNT(*) FILTER (WHERE publication_status = 'PROCESSING'
                               AND status IN ('PENDING', 'RETRY')
                               AND publication_lease_until <= CURRENT_TIMESTAMP AT TIME ZONE 'UTC') AS expired
                    FROM settlement_notification_outbox
                    WHERE status IN ('PENDING', 'RETRY', 'PROCESSING', 'FAILED')
                    """, (rs, row) -> Map.of("pending", rs.getDouble("pending"), "retry", rs.getDouble("retry"),
                    "processing", rs.getDouble("processing"), "failed", rs.getDouble("failed"),
                    "oldest", rs.getDouble("oldest"), "expired", rs.getDouble("expired")));
            snapshot = new Snapshot(now, values == null ? Map.of() : values);
        } catch (RuntimeException exception) {
            // A metrics scrape must not print DB errors or break during an outage; DB health owns that signal.
            snapshot = new Snapshot(now, snapshot.values());
        }
    }
    private record Snapshot(long loadedAt, Map<String, Double> values) {}
}
