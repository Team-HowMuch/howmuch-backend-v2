package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementOccurrence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public final class RecurringSettlementMapper {
    private RecurringSettlementMapper() {
    }

    public static RecurringSettlement toDomain(ResultSet row,
            List<RecurringSettlement.Participant> participants, List<RecurringSettlement.Expense> expenses)
            throws SQLException {
        String weekday = row.getString("day_of_week");
        String reason = row.getString("cancellation_reason");
        return new RecurringSettlement(row.getObject("id", UUID.class), row.getLong("group_id"),
                row.getLong("creator_id"), row.getObject("source_settlement_id", UUID.class),
                row.getString("title"), row.getString("category_code"), row.getLong("total_amount"),
                row.getLong("receiving_account_id"),
                new RecurrenceSchedule(RecurrenceSchedule.Frequency.valueOf(row.getString("frequency")),
                        weekday == null ? null : DayOfWeek.valueOf(weekday),
                        row.getObject("day_of_month", Integer.class), row.getObject("first_run_date", LocalDate.class)),
                time(row, "next_run_at"), RecurringSettlement.Status.valueOf(row.getString("status")),
                participants, expenses, row.getInt("failure_count"), time(row, "retry_at"),
                row.getString("last_failure_code"), time(row, "last_failure_at"),
                reason == null ? null : RecurringSettlement.CancellationReason.valueOf(reason),
                time(row, "cancelled_at"), time(row, "created_at"), time(row, "updated_at"), time(row, "deleted_at"));
    }

    public static RecurringSettlementOccurrence occurrence(ResultSet row, int number) throws SQLException {
        return new RecurringSettlementOccurrence(row.getObject("id", UUID.class),
                row.getObject("recurring_settlement_id", UUID.class), time(row, "scheduled_for"),
                row.getObject("settlement_id", UUID.class), time(row, "requested_at"));
    }

    public static MapSqlParameterSource parameters(RecurringSettlement value) {
        return new MapSqlParameterSource("id", value.id()).addValue("groupId", value.groupId())
                .addValue("creatorId", value.creatorId()).addValue("sourceId", value.sourceSettlementId())
                .addValue("title", value.title()).addValue("categoryCode", value.categoryCode())
                .addValue("total", value.totalAmount())
                .addValue("accountId", value.receivingAccountId())
                .addValue("frequency", value.schedule().frequency().name())
                .addValue("weekday", value.schedule().dayOfWeek() == null ? null : value.schedule().dayOfWeek().name())
                .addValue("monthDay", value.schedule().dayOfMonth())
                .addValue("firstDate", value.schedule().firstRunDate())
                .addValue("next", value.nextRunAt()).addValue("status", value.status().name())
                .addValue("failures", value.failureCount()).addValue("retry", value.retryAt())
                .addValue("failureCode", value.lastFailureCode()).addValue("failureAt", value.lastFailureAt())
                .addValue("reason", value.cancellationReason() == null ? null : value.cancellationReason().name())
                .addValue("cancelledAt", value.cancelledAt()).addValue("createdAt", value.createdAt())
                .addValue("updatedAt", value.updatedAt()).addValue("deletedAt", value.deletedAt());
    }

    private static LocalDateTime time(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class);
    }
}
