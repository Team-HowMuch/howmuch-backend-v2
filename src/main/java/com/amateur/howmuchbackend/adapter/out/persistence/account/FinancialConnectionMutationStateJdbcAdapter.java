package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.application.port.out.account.FinancialConnectionMutationStatePort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FinancialConnectionMutationStateJdbcAdapter
        implements FinancialConnectionMutationStatePort {
    private final JdbcTemplate jdbc;

    public FinancialConnectionMutationStateJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActiveReference(long user) {
        // Existing deployments must work before the opt-in feature's reviewed SQL is applied.
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT to_regclass('certificate_reference_operations') IS NOT NULL",
                        Boolean.class))) return false;
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM certificate_reference_operations WHERE user_id=?
 AND status IN ('QUEUED','RUNNING','UNKNOWN','ACTION_REQUIRED'))
""",
                        Boolean.class,
                        user));
    }

    public boolean hasUnresolvedCertificate(long user) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM certificate_pc_pairings p
 JOIN certificate_registration_sessions s ON s.id=p.registration_session_id
 WHERE p.paired_user_id=? AND (
  s.status IN ('REGISTERING','READY_FOR_CLAIM','CLAIM_RESERVED','BINDING_UNKNOWN')
  OR EXISTS(SELECT 1 FROM certificate_provider_command_inbox j WHERE j.session_id=s.id
   AND j.provider_request_started_at IS NOT NULL AND j.status IN ('PROCESSING','UNKNOWN'))
  OR EXISTS(SELECT 1 FROM certificate_provider_connections c WHERE c.session_id=s.id AND c.status='READY')))
""",
                        Boolean.class,
                        user));
    }
}
