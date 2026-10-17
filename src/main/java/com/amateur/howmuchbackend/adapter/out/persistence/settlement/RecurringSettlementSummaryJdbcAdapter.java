package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementSummaryReadModel;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 목록·홈 요약 전용 focused 쿼리. 참여자·지출을 엔티티로 적재하지 않고, 현재 사용자의 부담액만
 * LEFT JOIN으로 가져와 N+1을 피한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementSummaryJdbcAdapter implements LoadRecurringSettlementSummaryPort {
    private static final String SELECT = """
            SELECT r.id, r.group_id, r.title, r.category_code, r.frequency, r.day_of_week, r.day_of_month,
                   r.total_amount, r.next_run_at, r.status, r.failure_count, r.retry_at, r.last_failure_code,
                   r.cancellation_reason, p.amount AS viewer_amount
            FROM recurring_settlements r
            LEFT JOIN recurring_settlement_participants p
                   ON p.recurring_settlement_id = r.id AND p.user_id = :viewerId
            """;
    private static final String PARTICIPATING =
            " AND EXISTS (SELECT 1 FROM recurring_settlement_participants p2"
                    + " WHERE p2.recurring_settlement_id = r.id AND p2.user_id = :viewerId) ";
    private static final String OTHER =
            " AND NOT EXISTS (SELECT 1 FROM recurring_settlement_participants p2"
                    + " WHERE p2.recurring_settlement_id = r.id AND p2.user_id = :viewerId) ";
    private static final String CURSOR = """
             AND (r.created_at, r.id) < (SELECT created_at, id FROM recurring_settlements
                 WHERE id = :cursor AND group_id = :groupId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<RecurringSettlementSummaryReadModel> findGroupPage(Long groupId, RecurringSettlementScope scope,
            RecurringSettlementStatusFilter status, Long viewerId, UUID cursor, int limit) {
        var parameters = new MapSqlParameterSource("groupId", groupId).addValue("status", status.name())
                .addValue("viewerId", viewerId).addValue("limit", limit).addValue("cursor", cursor);
        String scopeClause = switch (scope) {
            case PARTICIPATING -> PARTICIPATING;
            case OTHER -> OTHER;
            case ALL -> "";
        };
        String cursorClause = cursor == null ? "" : CURSOR;
        String sql = SELECT + " WHERE r.group_id = :groupId AND r.status = :status" + scopeClause + cursorClause
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit";
        return jdbc.query(sql, parameters, this::map);
    }

    @Override
    public List<RecurringSettlementSummaryReadModel> findHome(Long viewerId, int limit) {
        var parameters = new MapSqlParameterSource("viewerId", viewerId).addValue("limit", limit);
        String sql = SELECT + """
                 JOIN group_members m ON m.group_id = r.group_id AND m.user_id = :viewerId AND m.status = 'ACTIVE'
                 JOIN groups g ON g.id = r.group_id AND g.status = 'ACTIVE'
                WHERE r.status = 'ACTIVE' AND p.amount IS NOT NULL
                ORDER BY r.next_run_at, r.id
                LIMIT :limit
                """;
        return jdbc.query(sql, parameters, this::map);
    }

    @Override
    public boolean cursorInPage(UUID cursor, Long groupId, RecurringSettlementScope scope,
            RecurringSettlementStatusFilter status, Long viewerId) {
        var parameters = new MapSqlParameterSource("cursor", cursor).addValue("groupId", groupId)
                .addValue("status", status.name()).addValue("viewerId", viewerId);
        String scopeClause = switch (scope) {
            case PARTICIPATING -> PARTICIPATING;
            case OTHER -> OTHER;
            case ALL -> "";
        };
        String sql = "SELECT EXISTS (SELECT 1 FROM recurring_settlements r"
                + " LEFT JOIN recurring_settlement_participants p"
                + " ON p.recurring_settlement_id = r.id AND p.user_id = :viewerId"
                + " WHERE r.id = :cursor AND r.group_id = :groupId AND r.status = :status" + scopeClause + ")";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, parameters, Boolean.class));
    }

    private RecurringSettlementSummaryReadModel map(ResultSet row, int number) throws SQLException {
        String weekday = row.getString("day_of_week");
        String reason = row.getString("cancellation_reason");
        return new RecurringSettlementSummaryReadModel(
                row.getObject("id", UUID.class),
                row.getLong("group_id"),
                row.getString("title"),
                row.getString("category_code"),
                RecurrenceSchedule.Frequency.valueOf(row.getString("frequency")),
                weekday == null ? null : DayOfWeek.valueOf(weekday),
                row.getObject("day_of_month", Integer.class),
                row.getLong("total_amount"),
                row.getObject("viewer_amount", Long.class),
                row.getObject("next_run_at", LocalDateTime.class),
                RecurringSettlement.Status.valueOf(row.getString("status")),
                row.getInt("failure_count"),
                row.getObject("retry_at", LocalDateTime.class),
                row.getString("last_failure_code"),
                reason == null ? null : RecurringSettlement.CancellationReason.valueOf(reason));
    }
}
