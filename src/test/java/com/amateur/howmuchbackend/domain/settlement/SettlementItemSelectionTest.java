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
    @DisplayName("수량만큼 곱한 금액과 단위를 계산한다")
    void multipliesAmountAndUnitsByQuantity() {
        SettlementItemSelection selection = selection(
                SettlementItemAllocationFraction.THIRD, 2
        );

        assertThat(selection.allocatedUnits()).isEqualTo(8);
        assertThat(selection.amountFor(item(30_000))).isEqualTo(20_000);
    }

    @Test
    @DisplayName("수량을 주지 않던 기존 저장과 같은 1개는 그대로 한 몫이다")
    void keepsSingleUnitBehaviour() {
        SettlementItemSelection selection = selection(
                SettlementItemAllocationFraction.HALF, 1
        );

        assertThat(selection.allocatedUnits()).isEqualTo(6);
        assertThat(selection.amountFor(item(10_000))).isEqualTo(5_000);
    }

    @Test
    @DisplayName("비율과 수량을 함께 바꾼다")
    void changesFractionAndUnits() {
        SettlementItemSelection changed = selection(SettlementItemAllocationFraction.THIRD, 1)
                .change(SettlementItemAllocationFraction.QUARTER, 3, NOW.plusMinutes(1));

        assertThat(changed.fraction()).isEqualTo(SettlementItemAllocationFraction.QUARTER);
        assertThat(changed.units()).isEqualTo(3);
        assertThat(changed.allocatedUnits()).isEqualTo(9);
        assertThat(changed.updatedAt()).isAfter(changed.createdAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3})
    @DisplayName("1개 미만이거나 품목 한 개 분량을 넘는 수량은 거부한다")
    void rejectsInvalidUnits(int units) {
        assertThatThrownBy(() -> selection(SettlementItemAllocationFraction.HALF, units))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION_UNITS));
    }

    @Test
    @DisplayName("품목 한 개를 정확히 채우는 수량은 허용한다")
    void allowsUnitsFillingWholeItem() {
        assertThat(selection(SettlementItemAllocationFraction.QUARTER, 4).allocatedUnits())
                .isEqualTo(SettlementItemAllocationFraction.WHOLE_UNITS);
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

    private SettlementItem item(long amount) {
        return new SettlementItem(
                ITEM_ID,
                SETTLEMENT_ID,
                UUID.randomUUID(),
                "삼겹살",
                3,
                amount,
                0,
                NOW,
                NOW
        );
    }
}
