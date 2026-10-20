package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.QueueNettedDepositDetectionPort;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NettedDepositDetectionJdbcAdapter implements QueueNettedDepositDetectionPort {
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value(
            "${howmuch.settlement.netting.enabled:false}")
    private boolean enabled;

    public void observed(long account, LocalDateTime now) {
        if (!enabled) return;
        var latest =
                jdbc.queryForObject(
                        "SELECT max(id) FROM account_transactions WHERE account_id=? AND"
                                + " transaction_type='INCOME'",
                        Long.class,
                        account);
        if (latest == null) return;
        jdbc.query(
                """
SELECT n.id,n.group_id,r.user_id FROM group_netting_monitored_accounts r
 JOIN group_settlement_nettings n ON n.id=r.netting_id WHERE r.account_id=? AND NOT EXISTS(SELECT 1 FROM group_settlement_nettings next WHERE next.predecessor_netting_id=n.id)
""",
                (rs, row) -> {
                    UUID netting = rs.getObject("id", UUID.class);
                    UUID key =
                            UUID.nameUUIDFromBytes(
                                    (netting + ":" + account + ":" + latest)
                                            .getBytes(StandardCharsets.UTF_8));
                    jdbc.update(
                            """
INSERT INTO group_netting_executions(id,group_id,target_netting_id,actor_id,kind,status,idempotency_key,next_attempt_at,deadline,created_at,updated_at)
VALUES(?,?,?,?,'DETECT','QUEUED',?,?,?,?,?) ON CONFLICT DO NOTHING
""",
                            UUID.randomUUID(),
                            rs.getLong("group_id"),
                            netting,
                            rs.getLong("user_id"),
                            key,
                            now,
                            now.plusMinutes(10),
                            now,
                            now);
                    return netting;
                },
                account);
    }
}
