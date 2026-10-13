package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.port.out.recap.LoadMonthlyRecapPort;
import com.amateur.howmuchbackend.application.port.out.recap.StoreMonthlyRecapPort;
import com.amateur.howmuchbackend.application.recap.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MonthlyRecapPersistenceAdapter implements LoadMonthlyRecapPort, StoreMonthlyRecapPort {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public java.util.List<YearMonth> findMonths(Long ownerId) {
        return jdbc.query("SELECT month_start FROM monthly_recaps WHERE owner_user_id=:owner ORDER BY month_start DESC",
                new MapSqlParameterSource("owner", ownerId), (row, i) -> YearMonth.from(row.getDate("month_start").toLocalDate()));
    }

    @Override
    public Optional<MonthlyRecap> find(Long ownerId, YearMonth month) {
        return jdbc.query("SELECT * FROM monthly_recaps WHERE owner_user_id = :owner AND month_start = :month",
                new MapSqlParameterSource("owner", ownerId).addValue("month", Date.valueOf(month.atDay(1))),
                this::map).stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(MonthlyRecap recap) {
        var c = recap.companion();
        var comparison = recap.comparison();
        var params = new MapSqlParameterSource("id", recap.id()).addValue("owner", recap.ownerId())
                .addValue("month", Date.valueOf(recap.yearMonth().atDay(1))).addValue("name", recap.ownerName())
                .addValue("count", recap.summary().completedSettlementCount())
                .addValue("amount", recap.summary().totalOwnAmount())
                .addValue("status", comparison.status().name()).addValue("previous", comparison.previousOwnAmount())
                .addValue("difference", comparison.amountDifference()).addValue("rate", comparison.changeRatePercent())
                .addValue("kind", c == null ? null : c.kind().name()).addValue("identity", c == null ? null : c.identityKey())
                .addValue("display", c == null ? null : c.displayName()).addValue("share", c == null ? null : c.shareDisplayName())
                .addValue("avatar", c == null ? null : c.avatarFileName()).addValue("ccount", c == null ? null : c.completedSettlementCount())
                .addValue("camount", c == null ? null : c.totalOwnAmount()).addValue("generated", Timestamp.valueOf(recap.generatedAt()));
        return jdbc.update("""
                INSERT INTO monthly_recaps (
                    id, owner_user_id, month_start, owner_name, completed_settlement_count, total_own_amount,
                    comparison_status, previous_own_amount, amount_difference, change_rate_percent,
                    companion_kind, companion_identity_key, companion_display_name, companion_share_display_name,
                    companion_avatar_file_name, companion_completed_settlement_count, companion_total_own_amount, generated_at
                ) VALUES (:id, :owner, :month, :name, :count, :amount, :status, :previous, :difference, :rate,
                          :kind, :identity, :display, :share, :avatar, :ccount, :camount, :generated)
                ON CONFLICT (owner_user_id, month_start) DO NOTHING
                """, params) == 1;
    }

    private MonthlyRecap map(ResultSet row, int index) throws SQLException {
        var kind = row.getString("companion_kind");
        var companion = kind == null ? null : new RecapCompanion(RecapCompanion.Kind.valueOf(kind),
                row.getString("companion_identity_key"), row.getString("companion_display_name"),
                row.getString("companion_share_display_name"), row.getString("companion_avatar_file_name"),
                row.getInt("companion_completed_settlement_count"), row.getLong("companion_total_own_amount"));
        return new MonthlyRecap(row.getObject("id", UUID.class), row.getLong("owner_user_id"),
                YearMonth.from(row.getDate("month_start").toLocalDate()), row.getString("owner_name"),
                new RecapSummary(row.getInt("completed_settlement_count"), row.getLong("total_own_amount")),
                new RecapComparison(RecapComparison.Status.valueOf(row.getString("comparison_status")),
                        row.getObject("previous_own_amount", Long.class), row.getObject("amount_difference", Long.class),
                        row.getBigDecimal("change_rate_percent")), companion,
                row.getTimestamp("generated_at").toLocalDateTime());
    }
}
