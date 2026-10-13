package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import com.amateur.howmuchbackend.application.recap.model.RecapComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RecapSummaryAndComparisonPolicyTest {
    private final RecapSummaryPolicy summary = new RecapSummaryPolicy();
    private final RecapComparisonPolicy comparison = new RecapComparisonPolicy();

    @Test
    void countsDistinctSettlementsAndOwnShareIncludingZero() {
        UUID id = UUID.randomUUID();
        var result = summary.summarize(List.of(fact(id, 10_000L, false), fact(id, 10_000L, false),
                fact(UUID.randomUUID(), null, true)));
        assertThat(result.completedSettlementCount()).isEqualTo(2);
        assertThat(result.totalOwnAmount()).isEqualTo(10_000);
        assertThat(summary.summarize(List.of()).completedSettlementCount()).isZero();
    }

    @Test
    void failsOnMissingOrInconsistentShareAndOverflow() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> summary.summarize(List.of(fact(id, null, false))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> summary.summarize(List.of(fact(id, 1L, false), fact(id, 2L, false))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> summary.summarize(List.of(fact(id, Long.MAX_VALUE, false),
                fact(UUID.randomUUID(), 1L, false)))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void noPreviousAndPreviousZeroHaveNoPercentage() {
        assertThat(comparison.compare(10, null)).isEqualTo(new RecapComparison(
                RecapComparison.Status.NO_PREVIOUS, null, null, null));
        var result = comparison.compare(10, 0L);
        assertThat(result.status()).isEqualTo(RecapComparison.Status.PREVIOUS_ZERO);
        assertThat(result.amountDifference()).isEqualTo(10);
        assertThat(result.changeRatePercent()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"0,0,UNCHANGED,0.0", "100,100,UNCHANGED,0.0", "200,100,INCREASED,100.0",
            "75,100,DECREASED,-25.0", "10001,10000,INCREASED,0.0", "9999,10000,DECREASED,0.0",
            "8001999999999999999,4000000000000000000,INCREASED,100.0",
            "8002000000000000000,4000000000000000000,INCREASED,100.1",
            "1998000000000000001,4000000000000000000,DECREASED,-50.0",
            "1998000000000000000,4000000000000000000,DECREASED,-50.1"})
    void usesActualDifferenceAndHalfUp(long current, long previous, String status, String rate) {
        var result = comparison.compare(current, previous);
        assertThat(result.status().name()).isEqualTo(status);
        assertThat(result.amountDifference()).isEqualTo(current - previous);
        assertThat(result.changeRatePercent()).isEqualByComparingTo(rate);
    }

    private RecapSourceFact fact(UUID id, Long amount, boolean zero) {
        return new RecapSourceFact(id, LocalDateTime.of(2026, 9, 1, 0, 0), amount, zero,
                null, null, null, null, null, null, false);
    }
}
