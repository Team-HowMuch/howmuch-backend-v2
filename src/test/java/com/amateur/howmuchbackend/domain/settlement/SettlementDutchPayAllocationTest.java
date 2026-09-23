package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("더치페이 배분 Domain")
class SettlementDutchPayAllocationTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 18, 0);

    @Test
    @DisplayName("0원도 직접 배분할 수 있도록 음수가 아닌 금액을 허용한다")
    void dutchPay_zeroAmount_allowed() {
        var allocation = SettlementDutchPayAllocation.create(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                0,
                NOW
        );

        assertThat(allocation.amount()).isZero();
    }

    @Test
    @DisplayName("음수 부담 금액을 명시적인 입력 오류로 거부한다")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> SettlementDutchPayAllocation.create(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                -1,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_DUTCH_PAY_AMOUNT));
    }
}
