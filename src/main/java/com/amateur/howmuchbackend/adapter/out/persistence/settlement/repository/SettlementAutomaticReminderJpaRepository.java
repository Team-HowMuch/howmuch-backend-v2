package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SettlementAutomaticReminderJpaRepository extends Repository<SettlementShareJpaEntity, UUID> {
    @Query(value = """
                SELECT s.id AS settlement_id, p.id AS participant_id, sh.id AS share_id, p.linked_user_id AS recipient_user_id
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
                """, nativeQuery = true)
    List<DueTarget> findDue(@Param("cutoff") LocalDateTime cutoff,
            @Param("lastReminderBefore") LocalDateTime lastReminderBefore, @Param("limit") int limit);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM user_notifications WHERE settlement_id=:settlementId
                AND recipient_user_id=:userId AND notification_type='SETTLEMENT_REQUEST')
            """, nativeQuery = true)
    boolean hasRequestNotification(@Param("settlementId") UUID settlementId, @Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM user_notifications
                WHERE recipient_user_id=:userId AND deduplication_key=:key)
            """, nativeQuery = true)
    boolean hasAutomaticReminder(@Param("userId") Long userId, @Param("key") String key);

    interface DueTarget {
        UUID getSettlementId();
        UUID getParticipantId();
        UUID getShareId();
        Long getRecipientUserId();
    }
}
