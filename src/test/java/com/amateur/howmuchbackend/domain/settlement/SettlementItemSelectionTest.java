package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("품목 선택 도메인 규칙")
class SettlementItemSelectionTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Test
    @DisplayName("수량만큼 곱한 단위를 계산한다")
    void itemSelection_quantity_multipliesUnits() {
        SettlementItemSelection selection = selection(
                SettlementItemAllocationFraction.THIRD, 2
        );

        assertThat(selection.allocatedUnits()).isEqualTo(8);
    }

    @Test
    @DisplayName("수량을 주지 않던 기존 저장과 같은 1개는 그대로 한 몫이다")
    void itemSelection_oneUnit_preservesDefaultBehavior() {
        SettlementItemSelection selection = selection(
                SettlementItemAllocationFraction.HALF, 1
        );

        assertThat(selection.allocatedUnits()).isEqualTo(6);
    }

    @Test
    @DisplayName("비율과 수량을 함께 바꾼다")
    void itemSelection_fractionAndUnits_changedTogether() {
        SettlementItemSelection changed = selection(SettlementItemAllocationFraction.THIRD, 1)
                .change(SettlementItemAllocationFraction.QUARTER, 3, NOW.plusMinutes(1));

        assertThat(changed.fraction()).isEqualTo(SettlementItemAllocationFraction.QUARTER);
        assertThat(changed.units()).isEqualTo(3);
        assertThat(changed.allocatedUnits()).isEqualTo(9);
        assertThat(changed.updatedAt()).isAfter(changed.createdAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("1개 미만인 수량은 거부한다")
    void rejectsInvalidUnits(int units) {
        assertThatThrownBy(() -> selection(SettlementItemAllocationFraction.HALF, units))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION_UNITS));
    }

    @Test
    @DisplayName("품목 한 개 분량을 넘는 수량도 도메인에서는 허용한다")
    void allowsUnitsBeyondSingleItem() {
        assertThat(selection(SettlementItemAllocationFraction.ONE, 3).allocatedUnits())
                .isEqualTo(SettlementItemAllocationFraction.WHOLE_UNITS * 3);
    }

    private SettlementItemSelection selection(
            SettlementItemAllocationFraction fraction,
            int units
    ) {
        return SettlementItemSelection.create(
                SETTLEMENT_ID,
                ITEM_ID,
                PARTICIPANT_ID,
                fraction,
                units,
                NOW
        );
    }
}
