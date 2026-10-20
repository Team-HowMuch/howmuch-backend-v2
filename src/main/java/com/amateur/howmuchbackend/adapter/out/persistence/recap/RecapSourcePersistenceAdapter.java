package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.LoadRecapSourcePort;
import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecapSourcePersistenceAdapter implements LoadRecapSourcePort {
    private final NamedParameterJdbcTemplate jdbc;
    private final com.amateur.howmuchbackend.adapter.out.persistence.recap.mapper.RecapAllocationFactsMapper allocationMapper;

    @Override
    public List<RecapSourceFact> findCompletedMonth(Long ownerId, YearMonth month) {
        var start = month.atDay(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        java.util.Map<UUID, com.amateur.howmuchbackend.application.recap.model.RecapAllocationFacts> allocations = new java.util.HashMap<>();
        return jdbc.query("""
                WITH target AS (
                    SELECT s.id, s.updated_at, s.allocation_type, s.total_amount, s.title,
                           link.created_at AS requested_at
                    FROM settlements s
                    LEFT JOIN settlement_links link ON link.settlement_id = s.id
                    WHERE s.status = 'COMPLETED' AND s.deleted_at IS NULL
                      AND s.updated_at >= :start AND s.updated_at < :end
                      AND (s.creator_id = :owner OR EXISTS (
                          SELECT 1 FROM settlement_participants member
                          WHERE member.settlement_id = s.id AND member.linked_user_id = :owner
                      ))
                ), own AS (
                    SELECT t.id, t.updated_at, t.total_amount, t.allocation_type, t.title, t.requested_at,
                           share.amount AS own_amount,
                           member.id AS own_participant_id, submission.id IS NOT NULL AS own_submitted
                    FROM target t
                    LEFT JOIN settlement_participants member
                      ON member.settlement_id = t.id AND member.linked_user_id = :owner
                    LEFT JOIN settlement_shares share
                      ON share.settlement_id = t.id AND share.payer_participant_id = member.id
                    LEFT JOIN settlement_item_submissions submission
                      ON submission.settlement_id = t.id AND submission.participant_id = member.id
                )
                SELECT own.id, own.updated_at, own.total_amount, own.allocation_type, own.own_amount,
                       own.title, own.requested_at,
                       own.own_participant_id, own.own_submitted, allocation.*,
                       p.id AS participant_id, p.linked_user_id, p.display_name,
                       account.name AS account_name, account.public_id, account.avatar_file_name, account.active
                FROM own
                LEFT JOIN LATERAL (
                    SELECT
                      (SELECT COALESCE(jsonb_agg(to_jsonb(i)), '[]') FROM settlement_items i WHERE i.settlement_id = own.id) AS items,
                      (SELECT COALESCE(jsonb_agg(to_jsonb(m)), '[]') FROM settlement_participants m WHERE m.settlement_id = own.id) AS people,
                      (SELECT COALESCE(jsonb_agg(to_jsonb(x)), '[]') FROM settlement_item_selections x WHERE x.settlement_id = own.id) AS selections,
                      (SELECT COUNT(*) FROM settlement_item_submissions x WHERE x.settlement_id = own.id) AS submissions,
                      (SELECT COALESCE(SUM(e.discount), 0) FROM settlement_expenses e WHERE e.settlement_id = own.id) AS receipt_discount,
                      (SELECT COALESCE(SUM(e.delivery_fee), 0) FROM settlement_expenses e WHERE e.settlement_id = own.id) AS delivery_fee
                    WHERE own.own_amount IS NULL AND own.allocation_type = 'ITEM_ALLOCATION'
                ) allocation ON TRUE
                LEFT JOIN settlement_participants p ON p.settlement_id = own.id
                LEFT JOIN users account ON account.id = p.linked_user_id
                ORDER BY own.id, p.id
                """, new MapSqlParameterSource("owner", ownerId)
                .addValue("start", Timestamp.valueOf(start)).addValue("end", Timestamp.valueOf(end)),
                (row, index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    com.amateur.howmuchbackend.application.recap.model.RecapAllocationFacts allocation = null;
                    if (row.getObject("own_amount") == null && "ITEM_ALLOCATION".equals(row.getString("allocation_type"))) {
                        allocation = allocations.get(id);
                        if (allocation == null) {
                            allocation = allocationMapper.map(id, row.getObject("own_participant_id", UUID.class),
                                    row.getBoolean("own_submitted"), row.getInt("submissions"), row.getLong("total_amount"),
                                    row.getString("items"), row.getString("people"), row.getString("selections"),
                                    row.getBigDecimal("receipt_discount").longValueExact(), row.getBigDecimal("delivery_fee").longValueExact());
                            allocations.put(id, allocation);
                        }
                    }
                    return new RecapSourceFact(id, row.getTimestamp("updated_at").toLocalDateTime(),
                            row.getObject("own_amount", Long.class), false,
                            row.getObject("participant_id", UUID.class), row.getObject("linked_user_id", Long.class),
                            row.getString("display_name"), row.getString("account_name"), row.getString("public_id"),
                            row.getString("avatar_file_name"), row.getBoolean("active"), allocation,
                            row.getObject("requested_at", java.time.LocalDateTime.class), row.getString("title"));
                });
    }
}
