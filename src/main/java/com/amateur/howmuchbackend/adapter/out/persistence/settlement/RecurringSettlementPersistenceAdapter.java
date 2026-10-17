package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.RecurringSettlementMapper;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveRecurringSettlementPort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.infra.persistence.config.PersistenceConcurrencyProperties;
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

/**
 * 반복 설정·참여자·지출 스냅샷 저장(V59). 도래 후보는 fresh(retry_at IS NULL)와 retry(retry_at 도래)
 * 두 partial index 가지를 UNION ALL로 합쳐 오래된 순서로 돌려준다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementPersistenceAdapter implements LoadRecurringSettlementPort, SaveRecurringSettlementPort {
    private static final String FRESH_DUE = """
            SELECT id, next_run_at FROM recurring_settlements
            WHERE status='ACTIVE' AND failure_count<3 AND retry_at IS NULL AND next_run_at<=:scheduledThrough
              AND (last_failure_code IS NULL OR last_failure_code='RECURRING_TRANSIENT_DATABASE_FAILURE')
            """;
    private static final String RETRY_DUE = """
            SELECT id, next_run_at FROM recurring_settlements
            WHERE status='ACTIVE' AND failure_count<3 AND retry_at IS NOT NULL
              AND next_run_at<=:scheduledThrough AND retry_at<=:now
              AND (last_failure_code IS NULL OR last_failure_code='RECURRING_TRANSIENT_DATABASE_FAILURE')
            """;
    private static final String CURSOR = " AND (next_run_at, id) > (:cursorTime, :cursorId) ";

    private final NamedParameterJdbcTemplate jdbc;
    private final PersistenceConcurrencyProperties concurrency;

    @Override
    public Optional<RecurringSettlement> findById(UUID id) {
        return find("SELECT * FROM recurring_settlements WHERE id=:id", id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecurringSettlement> findByIdForUpdate(UUID id) {
        jdbc.getJdbcTemplate().queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class,
                concurrency.lockTimeout().toMillis() + "ms");
        return find("SELECT * FROM recurring_settlements WHERE id=:id FOR UPDATE", id);
    }

    @Override
    public Optional<RecurringSettlement> findBySourceSettlementId(UUID sourceId) {
        return find("SELECT * FROM recurring_settlements WHERE source_settlement_id=:id", sourceId);
    }

    private Optional<RecurringSettlement> find(String sql, UUID id) {
        return jdbc.query(sql, Map.of("id", id), (row, number) -> {
            UUID recurringId = row.getObject("id", UUID.class);
            var participants = jdbc.query("""
                    SELECT user_id, amount, display_order FROM recurring_settlement_participants
                    WHERE recurring_settlement_id=:id ORDER BY display_order
                    """, Map.of("id", recurringId), (child, index) -> new RecurringSettlement.Participant(
                    child.getLong("user_id"), child.getLong("amount"), child.getInt("display_order")));
            var expenses = jdbc.query("""
                    SELECT description, amount, display_order FROM recurring_settlement_expenses
                    WHERE recurring_settlement_id=:id ORDER BY display_order
                    """, Map.of("id", recurringId), (child, index) -> new RecurringSettlement.Expense(
                    child.getString("description"), child.getLong("amount"), child.getInt("display_order")));
            return RecurringSettlementMapper.toDomain(row, participants, expenses);
        }).stream().findFirst();
    }

    @Override
    public List<DueOccurrence> findDue(LocalDateTime now, LocalDateTime scheduledThrough, DueOccurrence cursor,
            int limit) {
        var parameters = new MapSqlParameterSource("now", now).addValue("scheduledThrough", scheduledThrough)
                .addValue("limit", limit);
        String cursorClause = "";
        if (cursor != null) {
            cursorClause = CURSOR;
            parameters.addValue("cursorTime", cursor.scheduledFor()).addValue("cursorId", cursor.id());
        }
        // Each branch keeps its partial-index range; scheduledThrough bounds the scan tail to one cycle.
        String sql = "SELECT id, next_run_at FROM (" + FRESH_DUE + cursorClause + " UNION ALL "
                + RETRY_DUE + cursorClause + ") due ORDER BY next_run_at, id LIMIT :limit";
        return jdbc.query(sql, parameters, (row, number) -> new DueOccurrence(
                row.getObject("id", UUID.class), row.getObject("next_run_at", LocalDateTime.class)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(RecurringSettlement value) {
        jdbc.update("""
                INSERT INTO recurring_settlements (id, group_id, creator_id, source_settlement_id, title,
                    category_code, total_amount, receiving_account_id, frequency, day_of_week, day_of_month,
                    first_run_date, next_run_at, status, failure_count, retry_at, last_failure_code, last_failure_at,
                    cancellation_reason, cancelled_at, created_at, updated_at)
                VALUES (:id, :groupId, :creatorId, :sourceId, :title, :categoryCode, :total, :accountId, :frequency,
                    :weekday, :monthDay, :firstDate, :next, :status, :failures, :retry, :failureCode,
                    :failureAt, :reason, :cancelledAt, :createdAt, :updatedAt)
                """, RecurringSettlementMapper.parameters(value));
        for (var participant : value.participants()) {
            jdbc.update("""
                    INSERT INTO recurring_settlement_participants
                    (recurring_settlement_id, user_id, amount, display_order) VALUES (:id, :userId, :amount, :position)
                    """, Map.of("id", value.id(), "userId", participant.userId(), "amount", participant.amount(),
                    "position", participant.displayOrder()));
        }
        for (var expense : value.expenses()) {
            jdbc.update("""
                    INSERT INTO recurring_settlement_expenses
                    (id, recurring_settlement_id, description, amount, display_order)
                    VALUES (:expenseId, :id, :description, :amount, :position)
                    """, Map.of("expenseId", UUID.randomUUID(), "id", value.id(),
                    "description", expense.description(), "amount", expense.amount(),
                    "position", expense.displayOrder()));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(RecurringSettlement value) {
        if (jdbc.update("""
                UPDATE recurring_settlements SET next_run_at=:next, status=:status, failure_count=:failures,
                    retry_at=:retry, last_failure_code=:failureCode, last_failure_at=:failureAt,
                    cancellation_reason=:reason, cancelled_at=:cancelledAt, updated_at=:updatedAt
                WHERE id=:id
                """, RecurringSettlementMapper.parameters(value)) != 1) {
            throw new IllegalStateException("RECURRING_UPDATE_NOT_FOUND");
        }
    }
}
