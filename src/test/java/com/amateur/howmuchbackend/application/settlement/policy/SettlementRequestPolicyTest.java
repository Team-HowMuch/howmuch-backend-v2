package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 요청 사전조건 Policy")
class SettlementRequestPolicyTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 10, 0);

    private final SettlementRequestPolicy policy = new SettlementRequestPolicy();

    @Test
    @DisplayName("더치페이 참여자별 배분이 총액과 일치하면 같은 순서의 불변 Share를 만든다")
    void preparesDutchPayShares() {
        var shares = policy.prepareShares(
                settlement(SettlementAllocationType.DUTCH_PAY),
                participants(),
                expenses(),
                List.of(),
                List.of(
                        allocation(CREATOR_PARTICIPANT_ID, 4_000),
                        allocation(PAYER_PARTICIPANT_ID, 6_000)
                ),
                NOW
        );

        assertThat(shares).extracting(share -> share.payerParticipantId())
                .containsExactly(CREATOR_PARTICIPANT_ID, PAYER_PARTICIPANT_ID);
        assertThat(shares).extracting(share -> share.amount()).containsExactly(4_000L, 6_000L);
    }

    @Test
    @DisplayName("누락·0원·총액 불일치 더치페이 배분은 요청 확정하지 않는다")
    void rejectsInvalidDutchPayAllocations() {
        assertStatus(
                List.of(allocation(CREATOR_PARTICIPANT_ID, 10_000)),
                SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
        );
        assertStatus(
                List.of(
                        allocation(CREATOR_PARTICIPANT_ID, 0),
                        allocation(PAYER_PARTICIPANT_ID, 10_000)
                ),
                SettlementStatusCode.SETTLEMENT_SHARE_AMOUNT_REQUIRED
        );
        assertStatus(
                List.of(
                        allocation(CREATOR_PARTICIPANT_ID, 3_000),
                        allocation(PAYER_PARTICIPANT_ID, 6_000)
                ),
                SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH
        );
    }

    @Test
    @DisplayName("품목별 배분은 품목 합계가 지출과 일치하면 Share 없이 선택 단계를 연다")
    void validatesItemAllocationWithoutFinalShares() {
        UUID expenseId = expenses().getFirst().id();
        var shares = policy.prepareShares(
                settlement(SettlementAllocationType.ITEM_ALLOCATION),
                participants(),
                expenses(),
                List.of(
                        SettlementItem.fromReceipt(
                                SETTLEMENT_ID, expenseId, "식사", 1, 7_000, 0, NOW
                        ),
                        SettlementItem.fromReceipt(
                                SETTLEMENT_ID, expenseId, "음료", 1, 3_000, 1, NOW
                        )
                ),
                List.of(),
                NOW
        );

        assertThat(shares).isEmpty();
    }

    private void assertStatus(
            List<SettlementDutchPayAllocation> allocations,
            SettlementStatusCode expected
    ) {
        assertThatThrownBy(() -> policy.prepareShares(
                settlement(SettlementAllocationType.DUTCH_PAY),
                participants(),
                expenses(),
                List.of(),
                allocations,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(expected));
    }

    private Settlement settlement(SettlementAllocationType allocationType) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                10_000,
                allocationType,
                7L,
                SettlementStatus.DRAFT,
                NOW,
                NOW
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                new SettlementParticipant(
                        CREATOR_PARTICIPANT_ID,
                        SETTLEMENT_ID,
                        com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource.REGISTERED_USER,
                        1L,
                        null,
                        "최수아",
                        0,
                        NOW,
                        NOW
                ),
                new SettlementParticipant(
                        PAYER_PARTICIPANT_ID,
                        SETTLEMENT_ID,
                        com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource.REGISTERED_USER,
                        2L,
                        null,
                        "민선영",
                        1,
                        NOW,
                        NOW
                )
        );
    }

    private List<SettlementExpense> expenses() {
        return List.of(SettlementExpense.manual(
                SETTLEMENT_ID, "회식", 10_000, 0, NOW
        ));
    }

    private SettlementDutchPayAllocation allocation(UUID participantId, long amount) {
        return SettlementDutchPayAllocation.create(
                SETTLEMENT_ID, participantId, amount, NOW
        );
    }
}
