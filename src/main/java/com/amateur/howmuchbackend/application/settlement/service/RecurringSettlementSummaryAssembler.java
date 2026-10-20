package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementSummaryReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementDisplayState;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPresentationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 목록·홈 요약을 같은 규칙으로 조립한다. D-day는 KST 달력일 기준 실제 달력 거리(고정 30일 아님)이고,
 * 표시 상태는 발행 여부가 아니라 예정 회차와 실패 요약으로 결정한다.
 */
@Component
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class RecurringSettlementSummaryAssembler {
    private final SettlementCategoryCatalog categoryCatalog;
    private final Clock clock;
    private final RecurringSettlementPresentationAssembler presentation;

    public List<RecurringSettlementSummaryResult> assemble(List<RecurringSettlementSummaryReadModel> values) {
        return assemble(values, true);
    }

    public List<RecurringSettlementSummaryResult> assembleHome(List<RecurringSettlementSummaryReadModel> values) {
        return assemble(values, false);
    }

    private List<RecurringSettlementSummaryResult> assemble(List<RecurringSettlementSummaryReadModel> values, boolean withPresentation) {
        var categories = categoryCatalog.byCode();
        LocalDate today = kstToday();
        Map<UUID, RecurringSettlementPresentationResult> displays = withPresentation
                ? presentation.loadAll(values.stream().map(RecurringSettlementSummaryReadModel::id).toList()) : Map.of();
        return values.stream().map(value -> assemble(value, categories, today, displays.get(value.id()))).toList();
    }

    private RecurringSettlementSummaryResult assemble(RecurringSettlementSummaryReadModel value,
            java.util.Map<String, com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult> categories,
            LocalDate today, RecurringSettlementPresentationResult presentation) {
        var category = categories.get(value.categoryCode());
        Long daysUntil = daysUntilNextRun(value.nextRunAt(), today);
        return new RecurringSettlementSummaryResult(
                value.id(), value.groupId(), value.title(), value.categoryCode(),
                category == null ? null : category.name(), category == null ? null : category.icon(),
                value.frequency(), value.dayOfWeek(), value.dayOfMonth(), value.totalAmount(),
                value.viewerAmount(), value.viewerAmount() != null, value.status(),
                displayState(value, daysUntil), value.nextRunAt(), daysUntil, presentation);
    }

    private RecurringSettlementDisplayState displayState(RecurringSettlementSummaryReadModel value, Long daysUntil) {
        if (value.status() == RecurringSettlement.Status.CANCELLED) {
            return RecurringSettlementDisplayState.CANCELLED;
        }
        if (value.failureCount() >= RecurringSettlement.MAX_AUTOMATIC_ATTEMPTS
                || (value.lastFailureCode() != null
                    && !RecurringSettlement.TRANSIENT_FAILURE.equals(value.lastFailureCode()))) {
            return RecurringSettlementDisplayState.REVIEW;
        }
        if (value.retryAt() != null) {
            return RecurringSettlementDisplayState.RETRYING;
        }
        if (daysUntil == null) {
            return RecurringSettlementDisplayState.SCHEDULED;
        }
        if (daysUntil < 0) {
            return RecurringSettlementDisplayState.OVERDUE;
        }
        return daysUntil == 0 ? RecurringSettlementDisplayState.TODAY : RecurringSettlementDisplayState.SCHEDULED;
    }

    private Long daysUntilNextRun(LocalDateTime nextRunAt, LocalDate today) {
        if (nextRunAt == null) {
            return null;
        }
        LocalDate due = nextRunAt.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(RecurrenceSchedule.BUSINESS_ZONE).toLocalDate();
        return ChronoUnit.DAYS.between(today, due);
    }

    private LocalDate kstToday() {
        return LocalDateTime.now(clock).atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(RecurrenceSchedule.BUSINESS_ZONE).toLocalDate();
    }
}
