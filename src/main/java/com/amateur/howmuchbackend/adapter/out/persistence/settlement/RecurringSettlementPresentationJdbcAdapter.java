package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPresentationPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementPresentationJdbcAdapter implements LoadRecurringSettlementPresentationPort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Map<UUID, Presentation> loadAll(Collection<UUID> ids, Long viewerId) {
        if (ids.isEmpty()) return Map.of();
        var params = Map.of("ids", ids, "viewerId", viewerId);
        Map<UUID, List<Participant>> people = new HashMap<>();
        jdbc.query("""
                SELECT r.id, p.user_id, u.name, p.amount, p.display_order, m.id AS member_id,
                       CASE WHEN u.active THEN u.avatar_file_name END AS avatar_file_name
                FROM recurring_settlements r
                JOIN recurring_settlement_participants p ON p.recurring_settlement_id=r.id
                JOIN users u ON u.id=p.user_id
                LEFT JOIN groups g ON g.id=r.group_id AND g.status='ACTIVE'
                LEFT JOIN group_members viewer ON viewer.group_id=g.id AND viewer.user_id=:viewerId AND viewer.status='ACTIVE'
                LEFT JOIN group_members m ON m.group_id=g.id AND m.user_id=p.user_id AND m.status='ACTIVE' AND viewer.id IS NOT NULL
                WHERE r.id IN (:ids) AND r.deleted_at IS NULL
                ORDER BY r.id, p.display_order
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                people.computeIfAbsent(row.getObject("id", UUID.class), ignored -> new ArrayList<>())
                        .add(new Participant(row.getLong("user_id"), row.getString("name"), row.getLong("amount"),
                                row.getInt("display_order"), row.getObject("member_id", Long.class), row.getString("avatar_file_name"))));
        Map<UUID, Presentation> result = new HashMap<>();
        jdbc.query("""
                SELECT r.id, a.id AS account_id, bank.name AS bank_name, a.masked_number
                FROM recurring_settlements r
                JOIN account_records a ON a.id=r.receiving_account_id
                LEFT JOIN common_code_detail bank ON bank.group_code='BANK' AND bank.code=a.organization AND bank.use_yn
                WHERE r.id IN (:ids) AND r.deleted_at IS NULL
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    UUID id = row.getObject("id", UUID.class);
                    result.put(id, new Presentation(List.copyOf(people.getOrDefault(id, List.of())),
                            new ReceivingAccount(row.getLong("account_id"), row.getString("bank_name"), row.getString("masked_number"))));
                });
        return Map.copyOf(result);
    }
}
