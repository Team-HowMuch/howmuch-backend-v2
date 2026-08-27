package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("품목 선택 비율")
class SettlementItemAllocationFractionTest {
    @Test
    @DisplayName("1·1/2·1/3·1/4를 12 단위로 표현한다")
    void exposesWholeUnits() {
        assertThat(SettlementItemAllocationFraction.ONE.units()).isEqualTo(12);
        assertThat(SettlementItemAllocationFraction.HALF.units()).isEqualTo(6);
        assertThat(SettlementItemAllocationFraction.THIRD.units()).isEqualTo(4);
        assertThat(SettlementItemAllocationFraction.QUARTER.units()).isEqualTo(3);
    }

    @Test
    @DisplayName("정확히 나누어지는 품목의 원 단위 금액을 계산한다")
    void calculatesExactWonAmount() {
        assertThat(SettlementItemAllocationFraction.HALF.amountOf(1_000)).isEqualTo(500);
        assertThat(SettlementItemAllocationFraction.THIRD.amountOf(999)).isEqualTo(333);
        assertThat(SettlementItemAllocationFraction.QUARTER.amountOf(1_000)).isEqualTo(250);
    }

    @Test
    @DisplayName("반올림이 필요한 비율은 거부한다")
    void rejectsInexactWonAmount() {
        assertThatThrownBy(() -> SettlementItemAllocationFraction.THIRD.amountOf(1_000))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_ITEM_ALLOCATION_AMOUNT_NOT_EXACT
                        ));
    }
}
