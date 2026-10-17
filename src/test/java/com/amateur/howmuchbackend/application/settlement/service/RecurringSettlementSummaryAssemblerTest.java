package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementSummaryReadModel;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementDisplayState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecurringSettlementSummaryAssemblerTest {
    // 2026-10-18T03:00:00Z == 2026-10-18 12:00 KST, so the KST day is 2026-10-18.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneOffset.UTC);
    private static final long GROUP = 7L;

    private final SettlementCategoryCatalog catalog = mock(SettlementCategoryCatalog.class);
    private final RecurringSettlementSummaryAssembler assembler =
            new RecurringSettlementSummaryAssembler(catalog, CLOCK);

    RecurringSettlementSummaryAssemblerTest() {
        when(catalog.byCode()).thenReturn(Map.of("HOUSING_TELECOM",
                new SettlementCategoryResult("HOUSING_TELECOM", "주거 / 통신", "월세", "housing-telecom")));
    }

    @Test
    void assemblesCategoryDdayAmountAndParticipation() {
        var scheduled = assemble(model(LocalDateTime.of(2026, 10, 21, 0, 0), 12_000L,
                RecurringSettlement.Status.ACTIVE, 0, null, null));
        assertThat(scheduled.categoryName()).isEqualTo("주거 / 통신");
        assertThat(scheduled.categoryIcon()).isEqualTo("housing-telecom");
        assertThat(scheduled.daysUntilNextRun()).isEqualTo(3L);
        assertThat(scheduled.displayState()).isEqualTo(RecurringSettlementDisplayState.SCHEDULED);
        assertThat(scheduled.isParticipant()).isTrue();
        assertThat(scheduled.myAmount()).isEqualTo(12_000L);

        var today = assemble(model(LocalDateTime.of(2026, 10, 18, 0, 0), null,
                RecurringSettlement.Status.ACTIVE, 0, null, null));
        assertThat(today.daysUntilNextRun()).isZero();
        assertThat(today.displayState()).isEqualTo(RecurringSettlementDisplayState.TODAY);
        assertThat(today.isParticipant()).isFalse();
        assertThat(today.myAmount()).isNull();
    }

    @Test
    void overdueRetryingReviewAndCancelledStates() {
        assertThat(assemble(model(LocalDateTime.of(2026, 10, 16, 0, 0), 1L,
                RecurringSettlement.Status.ACTIVE, 0, null, null)).displayState())
                .isEqualTo(RecurringSettlementDisplayState.OVERDUE);
        assertThat(assemble(model(LocalDateTime.of(2026, 10, 15, 0, 0), 1L,
                RecurringSettlement.Status.ACTIVE, 1, LocalDateTime.of(2026, 10, 18, 1, 0),
                RecurringSettlement.TRANSIENT_FAILURE)).displayState())
                .isEqualTo(RecurringSettlementDisplayState.RETRYING);
        assertThat(assemble(model(LocalDateTime.of(2026, 10, 15, 0, 0), 1L,
                RecurringSettlement.Status.ACTIVE, 3, null, RecurringSettlement.TRANSIENT_FAILURE)).displayState())
                .isEqualTo(RecurringSettlementDisplayState.REVIEW);
        var cancelled = assemble(model(null, null, RecurringSettlement.Status.CANCELLED, 0, null, null));
        assertThat(cancelled.displayState()).isEqualTo(RecurringSettlementDisplayState.CANCELLED);
        assertThat(cancelled.daysUntilNextRun()).isNull();
    }

    private RecurringSettlementSummaryResult assemble(RecurringSettlementSummaryReadModel model) {
        return assembler.assemble(List.of(model)).getFirst();
    }

    private RecurringSettlementSummaryReadModel model(LocalDateTime nextRunAt, Long viewerAmount,
            RecurringSettlement.Status status, int failureCount, LocalDateTime retryAt, String lastFailureCode) {
        return new RecurringSettlementSummaryReadModel(UUID.randomUUID(), GROUP, "월세", "HOUSING_TELECOM",
                RecurrenceSchedule.Frequency.MONTHLY, null, 31, 20_000L, viewerAmount, nextRunAt, status,
                failureCount, retryAt, lastFailureCode, null);
    }
}
