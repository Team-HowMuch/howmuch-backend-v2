package com.amateur.howmuchbackend.domain.settlement.recurring;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.support.RecurringSettlementFixtures;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurringSettlementTest {
    @Test
    void automaticFailures_backOffThenStopAfterThreeAttempts() {
        var value = RecurringSettlementFixtures.template();
        var first = value.failed(true, true, value.nextRunAt());
        assertThat(first.failureCount()).isEqualTo(1);
        assertThat(first.retryAt()).isEqualTo(value.nextRunAt().plusMinutes(1));
        assertThat(first.automaticDue(value.nextRunAt())).isFalse();
        var second = first.failed(true, true, first.retryAt());
        assertThat(second.retryAt()).isEqualTo(first.retryAt().plusMinutes(5));
        var third = second.failed(true, true, second.retryAt());
        assertThat(third.failureCount()).isEqualTo(3);
        assertThat(third.retryAt()).isNull();
        assertThat(third.nextRunAt()).isEqualTo(value.nextRunAt());
        assertThat(third.requiresManualReview()).isTrue();
        assertThat(third.automaticDue(value.nextRunAt().plusYears(1))).isFalse();
    }

    @Test
    void manualFailure_doesNotConsumeBudgetAndSuccessResetsTheOccurrenceState() {
        var value = RecurringSettlementFixtures.template();
        var failed = value.failed(true, true, value.nextRunAt()).failed(true, false, value.nextRunAt());
        assertThat(failed.failureCount()).isEqualTo(1);
        assertThat(failed.requiresManualReview()).isTrue();
        var published = failed.published(value.nextRunAt(), value.nextRunAt().plusDays(2));
        assertThat(published.failureCount()).isZero();
        assertThat(published.lastFailureCode()).isNull();
        assertThat(published.nextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
    }

    @Test
    void unexpectedFailure_requiresReviewImmediately() {
        var value = RecurringSettlementFixtures.template()
                .failed(false, true, RecurringSettlementFixtures.FIRST);
        assertThat(value.failureCount()).isEqualTo(1);
        assertThat(value.retryAt()).isNull();
        assertThat(value.requiresManualReview()).isTrue();
    }

    @Test
    void cancellation_isIdempotentAndStopsFutureRuns() {
        var value = RecurringSettlementFixtures.template();
        var cancelled = value.cancel(RecurringSettlement.CancellationReason.USER_CANCELLED, value.nextRunAt());
        assertThat(cancelled.nextRunAt()).isNull();
        assertThat(cancelled.automaticDue(value.createdAt().plusYears(1))).isFalse();
        assertThat(cancelled.cancel(RecurringSettlement.CancellationReason.ACCOUNT_UNAVAILABLE,
                value.createdAt().plusYears(1))).isEqualTo(cancelled);
    }

    @Test
    void zeroAmountAndUnbalancedSnapshots_areRejected() {
        assertThatThrownBy(() -> new RecurringSettlement.Participant(1L, 0, 0))
                .isInstanceOf(ApplicationException.class);
        var value = RecurringSettlementFixtures.template();
        assertThatThrownBy(() -> RecurringSettlement.create(value.groupId(), value.creatorId(),
                value.sourceSettlementId(), value.title(), value.categoryCode(), value.totalAmount(),
                value.receivingAccountId(), value.schedule(),
                List.of(new RecurringSettlement.Participant(value.creatorId(), 1, 0),
                        new RecurringSettlement.Participant(999L, 1, 1)), value.expenses(), value.createdAt()))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void categoryCodeMustBeStorable() {
        var value = RecurringSettlementFixtures.template();
        assertThatThrownBy(() -> RecurringSettlement.create(value.groupId(), value.creatorId(),
                value.sourceSettlementId(), value.title(), "lowercase", value.totalAmount(),
                value.receivingAccountId(), value.schedule(), value.participants(), value.expenses(),
                value.createdAt())).isInstanceOf(ApplicationException.class);
    }
}
