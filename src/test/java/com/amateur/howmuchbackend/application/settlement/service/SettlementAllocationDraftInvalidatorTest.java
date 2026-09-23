package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 배분 초안 무효화")
class SettlementAllocationDraftInvalidatorTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 18, 0);

    @Mock
    private SettlementDutchPayAllocationStorePort allocationStorePort;

    @Test
    @DisplayName("선택된 배분 유형과 참여자별 더치페이 초안을 함께 제거한다")
    void allocationDraft_selectedType_invalidatesAndClears() {
        var invalidator = new SettlementAllocationDraftInvalidator(allocationStorePort);
        Settlement settlement = settlement(SettlementAllocationType.DUTCH_PAY);

        Settlement invalidated = invalidator.invalidate(settlement, NOW.plusMinutes(1));

        verify(allocationStorePort).deleteAllBySettlementId(SETTLEMENT_ID);
        assertThat(invalidated.allocationType()).isNull();
        assertThat(invalidated.updatedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    @DisplayName("배분을 선택하지 않은 정산은 불필요한 삭제와 저장 변경을 만들지 않는다")
    void keepsUnselectedSettlement() {
        var invalidator = new SettlementAllocationDraftInvalidator(allocationStorePort);
        Settlement settlement = settlement(null);

        Settlement unchanged = invalidator.invalidate(settlement, NOW.plusMinutes(1));

        assertThat(unchanged).isSameAs(settlement);
        verifyNoInteractions(allocationStorePort);
    }

    private Settlement settlement(SettlementAllocationType allocationType) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                1_000,
                allocationType,
                SettlementStatus.DRAFT,
                NOW,
                NOW
        );
    }
}
