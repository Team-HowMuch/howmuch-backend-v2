package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementAutomaticReminderTargetAdapter implements SettlementAutomaticReminderTargetPort {
    private final JdbcClient jdbc;

    @Override
    public List<Target> findDue(LocalDateTime shareCreatedBefore, LocalDateTime lastReminderBefore, int limit) {
        return jdbc.sql("""
                SELECT s.id AS settlement_id, p.id AS participant_id, sh.id AS share_id, p.linked_user_id
                FROM settlement_shares sh
                JOIN settlements s ON s.id=sh.settlement_id
                JOIN settlement_participants p ON p.id=sh.payer_participant_id AND p.settlement_id=s.id
                JOIN users u ON u.id=p.linked_user_id
                WHERE sh.created_at<=:cutoff AND u.active=true AND p.linked_user_id<>s.creator_id
                    AND s.status IN ('REQUESTED','AWAITING_DEPOSIT','SUSPENDED')
                    AND COALESCE((SELECT SUM(t.amount) FROM settlement_deposits d
                        JOIN account_transactions t ON t.id=d.account_transaction_id
                        WHERE d.share_id=sh.id),0)=0
                    AND EXISTS (SELECT 1 FROM user_notifications n WHERE n.settlement_id=s.id
                        AND n.recipient_user_id=p.linked_user_id AND n.notification_type='SETTLEMENT_REQUEST')
                    AND NOT EXISTS (SELECT 1 FROM user_notifications n WHERE n.recipient_user_id=p.linked_user_id
                        AND n.deduplication_key='AUTO_REMINDER:' || CAST(sh.id AS text))
                    AND NOT EXISTS (SELECT 1 FROM user_notifications n WHERE n.settlement_id=s.id
                        AND n.recipient_user_id=p.linked_user_id AND n.notification_type='SETTLEMENT_REMINDER'
                        AND n.created_at>:lastReminderBefore)
                ORDER BY sh.created_at,sh.id LIMIT :limit
                """).param("cutoff", shareCreatedBefore).param("lastReminderBefore", lastReminderBefore).param("limit", limit)
                .query((rs, row) -> new Target(rs.getObject("settlement_id", UUID.class),
                        rs.getObject("participant_id", UUID.class), rs.getObject("share_id", UUID.class),
                        rs.getLong("linked_user_id"))).list();
    }

    @Override
    public boolean hasRequestNotification(UUID settlementId, Long recipientUserId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM user_notifications WHERE settlement_id=:settlementId
                    AND recipient_user_id=:userId AND notification_type='SETTLEMENT_REQUEST')
                """).param("settlementId", settlementId).param("userId", recipientUserId).query(Boolean.class).single();
    }

    @Override
    public boolean hasAutomaticReminder(UUID shareId, Long recipientUserId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_notifications WHERE recipient_user_id=:userId AND deduplication_key=:key)")
                .param("userId", recipientUserId).param("key", "AUTO_REMINDER:" + shareId).query(Boolean.class).single();
    }
}
