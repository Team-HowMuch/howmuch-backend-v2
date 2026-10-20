package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettedTransferReminderStorePort;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettedTransferReminderJdbcAdapter implements NettedTransferReminderStorePort {
    private final JdbcTemplate jdbc;

    public List<Candidate> automaticCandidates(LocalDateTime now, int limit) {
        return jdbc.query(
                """
SELECT n.group_id,n.id,t.id transfer_id FROM group_settlement_nettings n
 JOIN group_netted_transfers t ON t.netting_id=n.id LEFT JOIN netted_transfer_reminders r ON r.transfer_id=t.id
WHERE n.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM group_netting_executions e WHERE e.group_id=n.group_id AND e.kind='CREATE' AND e.status IN ('QUEUED','SYNCING','RETRY')) AND COALESCE(r.last_at,t.created_at)<=?
 AND NOT EXISTS(SELECT 1 FROM netting_deposit_reviews d WHERE d.netting_id=n.id AND d.status='OPEN')
 AND NOT EXISTS(SELECT 1 FROM netted_transfer_deposits d WHERE d.transfer_id=t.id)
 AND NOT EXISTS(SELECT 1 FROM netting_deposit_reviews r WHERE r.resolved_transfer_id=t.id AND r.status='APPLIED_TO_NETTING')
ORDER BY COALESCE(r.last_at,t.created_at),t.id LIMIT ?
""",
                (rs, i) ->
                        new Candidate(
                                rs.getLong("group_id"),
                                rs.getObject("id", UUID.class),
                                rs.getObject("transfer_id", UUID.class)),
                now.minusHours(12),
                limit);
    }

    public OptionalLong reserve(UUID id, LocalDateTime now, Duration interval) {
        var sequence =
                jdbc.query(
                        """
INSERT INTO netted_transfer_reminders(transfer_id,last_at,sequence) VALUES(?,?,1)
ON CONFLICT(transfer_id) DO UPDATE SET last_at=EXCLUDED.last_at,sequence=netted_transfer_reminders.sequence+1
WHERE netted_transfer_reminders.last_at<=? RETURNING sequence
""",
                        (rs, i) -> rs.getLong(1),
                        id,
                        now,
                        now.minus(interval));
        return sequence.isEmpty() ? OptionalLong.empty() : OptionalLong.of(sequence.getFirst());
    }
}
