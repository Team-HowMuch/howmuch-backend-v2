package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReminderFactsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementReminderFactsPersistenceAdapter implements LoadSettlementReminderFactsPort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<ParticipantFacts> findAllBySettlementIds(Collection<UUID> settlementIds) {
        if (settlementIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT settlement.id AS settlement_id,
                       participant.id AS participant_id,
                       (participant.linked_user_id IS NOT NULL
                           AND participant.linked_user_id <> settlement.creator_id) AS linked_non_creator,
                       submission.id IS NOT NULL AS submitted,
                       share.id IS NOT NULL AS share_present,
                       COALESCE(confirmed.amount, 0) AS confirmed_amount
                  FROM settlements settlement
                  JOIN settlement_participants participant ON participant.settlement_id = settlement.id
                  LEFT JOIN settlement_item_submissions submission
                    ON submission.settlement_id = settlement.id AND submission.participant_id = participant.id
                  LEFT JOIN settlement_shares share
                    ON share.settlement_id = settlement.id AND share.payer_participant_id = participant.id
                  LEFT JOIN (
                      SELECT deposit.share_id, SUM(account_transaction.amount) AS amount
                        FROM settlement_deposits deposit
                        JOIN account_transactions account_transaction ON account_transaction.id = deposit.account_transaction_id
                       WHERE deposit.settlement_id IN (:settlementIds)
                       GROUP BY deposit.share_id
                  ) confirmed ON confirmed.share_id = share.id
                 WHERE settlement.id IN (:settlementIds) AND settlement.deleted_at IS NULL
                """, Map.of("settlementIds", settlementIds), (row, rowNum) -> new ParticipantFacts(
                row.getObject("settlement_id", UUID.class),
                row.getObject("participant_id", UUID.class),
                row.getBoolean("linked_non_creator"),
                row.getBoolean("submitted"),
                row.getBoolean("share_present"),
                row.getLong("confirmed_amount")
        ));
    }
}
