package com.amateur.howmuchbackend.adapter.out.persistence.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateRelayStorePort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRelayJdbcAdapter implements CertificateRelayStorePort {
    private final JdbcTemplate jdbc;

    public CertificateRelayJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String table(boolean result) {
        return result ? "certificate_provider_result_outbox" : "certificate_command_outbox";
    }

    public Optional<CertificatePublication> claimPublication(boolean result, LocalDateTime now) {
        String table = table(result);
        var ids =
                jdbc.query(
                        "WITH candidate AS (SELECT message_id FROM "
                                + table
                                + " WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=?"
                                + " ORDER BY next_attempt_at,message_id FOR UPDATE SKIP LOCKED"
                                + " LIMIT 1) UPDATE "
                                + table
                                + " o SET"
                                + " status='PROCESSING',attempt_count=attempt_count+1,lease_generation=lease_generation+1,lease_token=?,processing_started_at=?,lease_expires_at=?,next_attempt_at=NULL,updated_at=?"
                                + " FROM candidate c WHERE o.message_id=c.message_id RETURNING"
                                + " o.message_id",
                        (r, n) -> r.getObject(1, UUID.class),
                        now,
                        UUID.randomUUID(),
                        now,
                        now.plusSeconds(30),
                        now);
        if (ids.isEmpty()) return Optional.empty();
        UUID id = ids.getFirst();
        return jdbc
                .query(
                        "SELECT o.*,s.organization_code,s.expires_at FROM "
                                + table
                                + " o JOIN certificate_registration_sessions s ON s.id=o.session_id"
                                + " WHERE o.message_id=?",
                        (r, n) -> {
                            UUID session = r.getObject("session_id", UUID.class);
                            long version = r.getLong("session_version");
                            var created = r.getObject("created_at", LocalDateTime.class);
                            ReceiveCertificateCommand command =
                                    result
                                            ? null
                                            : new ReceiveCertificateCommand(
                                                    id,
                                                    session,
                                                    version,
                                                    r.getString("command_type"),
                                                    r.getString("organization_code"),
                                                    r.getObject("upload_ref", UUID.class),
                                                    r.getString("command_type").equals("REGISTER")
                                                            ? r.getObject(
                                                                    "expires_at",
                                                                    LocalDateTime.class)
                                                            : null,
                                                    created);
                            ReceiveCertificateResultCommand event =
                                    result
                                            ? new ReceiveCertificateResultCommand(
                                                    id,
                                                    session,
                                                    version,
                                                    r.getString("message_type"),
                                                    r.getObject("connection_ref", UUID.class),
                                                    r.getString("event_failure_code"),
                                                    created)
                                            : null;
                            return new CertificatePublication(
                                    result,
                                    id,
                                    r.getObject("lease_token", UUID.class),
                                    r.getLong("lease_generation"),
                                    command,
                                    event);
                        },
                        id)
                .stream()
                .findFirst();
    }

    public boolean completePublication(
            CertificatePublication p, boolean acknowledged, LocalDateTime now) {
        return jdbc.update(
                        "UPDATE "
                                + table(p.result())
                                + " SET status=?,last_failure_code=?,completed_at=?,next_attempt_at=?,lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,updated_at=?"
                                + " WHERE message_id=? AND status='PROCESSING' AND lease_token=?"
                                + " AND lease_generation=? AND lease_expires_at>?",
                        acknowledged ? "PUBLISHED" : "RETRY",
                        acknowledged ? null : "BROKER_UNAVAILABLE",
                        acknowledged ? now : null,
                        acknowledged ? null : now.plusSeconds(5),
                        now,
                        p.messageId(),
                        p.leaseToken(),
                        p.generation(),
                        now)
                == 1;
    }

    public void recoverPublications(LocalDateTime now) {
        for (boolean result : List.of(false, true))
            jdbc.update(
                    "UPDATE "
                            + table(result)
                            + " SET status='RETRY',last_failure_code='LEASE_EXPIRED',next_attempt_at=?,lease_token=NULL,processing_started_at=NULL,lease_expires_at=NULL,updated_at=?"
                            + " WHERE status='PROCESSING' AND lease_expires_at<=?",
                    now,
                    now,
                    now);
    }
}
