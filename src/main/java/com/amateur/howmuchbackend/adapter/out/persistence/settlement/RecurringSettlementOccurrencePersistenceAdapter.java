package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.RecurringSettlementMapper;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementOccurrence;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementOccurrencePersistenceAdapter implements RecurringSettlementOccurrenceStorePort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Optional<RecurringSettlementOccurrence> findById(UUID id) {
        return jdbc.query("SELECT * FROM recurring_settlement_occurrences WHERE id=:id", Map.of("id", id),
                RecurringSettlementMapper::occurrence).stream().findFirst();
    }

    @Override
    public Optional<RecurringSettlementOccurrence> findBySchedule(UUID id, LocalDateTime scheduledFor) {
        return jdbc.query("""
                SELECT * FROM recurring_settlement_occurrences WHERE recurring_settlement_id=:id AND scheduled_for=:time
                """, Map.of("id", id, "time", scheduledFor), RecurringSettlementMapper::occurrence)
                .stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(RecurringSettlementOccurrence value) {
        jdbc.update("""
                INSERT INTO recurring_settlement_occurrences
                (id, recurring_settlement_id, scheduled_for, settlement_id, requested_at)
                VALUES (:id, :recurringId, :time, :settlementId, :requestedAt)
                """, Map.of("id", value.id(), "recurringId", value.recurringSettlementId(),
                "time", value.scheduledFor(), "settlementId", value.settlementId(),
                "requestedAt", value.requestedAt()));
    }

    @Override
    public List<RecurringSettlementOccurrence> findByRecurringSettlementId(UUID id, UUID cursor, int limit) {
        var parameters = new MapSqlParameterSource("id", id).addValue("cursor", cursor).addValue("limit", limit);
        String cursorClause = cursor == null ? "" : """
                AND (scheduled_for, id) < (SELECT scheduled_for, id FROM recurring_settlement_occurrences
                    WHERE recurring_settlement_id=:id AND id=:cursor)
                """;
        return jdbc.query("SELECT * FROM recurring_settlement_occurrences WHERE recurring_settlement_id=:id "
                        + cursorClause + " ORDER BY scheduled_for DESC, id DESC LIMIT :limit",
                parameters, RecurringSettlementMapper::occurrence);
    }
}
