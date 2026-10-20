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
    void settlementRequest_dutchPayTotalMatches_createsOrderedShares() {
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
    @DisplayName("누락·총액 불일치 더치페이 배분은 요청 확정하지 않는다")
    void settlementRequest_dutchPayInvalidAllocation_rejected() {
        assertStatus(
                List.of(allocation(CREATOR_PARTICIPANT_ID, 10_000)),
                SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
        );
        assertStatus(
                List.of(
                        allocation(CREATOR_PARTICIPANT_ID, 0),
                        allocation(PAYER_PARTICIPANT_ID, 0)
                ),
                SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH
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
    void settlementRequest_balancedItemAllocation_opensWithoutShares() {
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

    @Test
    @DisplayName("품목·전체 할인 후 금액이 지출 결제액과 같으면 정산 요청을 허용한다")
    void settlementRequest_discountedReceipt_matchesNetExpense() {
        Settlement settlement = new Settlement(
                SETTLEMENT_ID, 1L, "영수증", 9_000,
                SettlementAllocationType.ITEM_ALLOCATION, 7L,
                SettlementStatus.DRAFT, NOW, NOW
        );
        SettlementExpense expense = SettlementExpense.receiptOcr(
                SETTLEMENT_ID, "discounted-receipt", "가게", 9_000,
                null, 0, NOW, 1_000
        );
        SettlementItem item = SettlementItem.fromReceipt(
                SETTLEMENT_ID, expense.id(), "식사", 1, 12_000, 0, NOW,
                2_000, List.of()
        );

        assertThat(policy.prepareShares(
                settlement, participants(), List.of(expense), List.of(item), List.of(), NOW
        )).isEmpty();
    }

    @Test
    @DisplayName("정산 요청의 품목 합계 검증에 배달비를 포함한다")
    void settlementRequest_deliveryFee_includedInItemTotal() {
        var expense = SettlementExpense.receiptOcr(
                SETTLEMENT_ID, "fee", "식사", 12_000, null, 0, NOW, 1_000, 3_000);
        var item = SettlementItem.fromReceipt(SETTLEMENT_ID, expense.id(), "식사", 1, 10_000, 0, NOW);
        var settlement = new Settlement(SETTLEMENT_ID, 1L, "배달", 12_000,
                SettlementAllocationType.ITEM_ALLOCATION, 7L, SettlementStatus.DRAFT, NOW, NOW);

        assertThat(policy.prepareShares(settlement, participants(), List.of(expense), List.of(item), List.of(), NOW))
                .isEmpty();
        assertThatThrownBy(() -> policy.validateItemTotal(List.of(item), List.of(expense), 9_000))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_TOTAL_MISMATCH));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,10000", "10000,0"})
    @DisplayName("생성자 또는 다른 참여자의 0원 배분은 Share 없이 요청한다")
    void settlementRequest_zeroAmount_createsOnlyPositiveShare(long creatorAmount, long payerAmount) {
        var shares = policy.prepareShares(
                settlement(SettlementAllocationType.DUTCH_PAY), participants(), expenses(), List.of(),
                List.of(allocation(CREATOR_PARTICIPANT_ID, creatorAmount),
                        allocation(PAYER_PARTICIPANT_ID, payerAmount)), NOW);

        assertThat(shares).singleElement().satisfies(share -> {
            assertThat(share.amount()).isEqualTo(10_000);
            assertThat(share.payerParticipantId()).isEqualTo(
                    creatorAmount > 0 ? CREATOR_PARTICIPANT_ID : PAYER_PARTICIPANT_ID);
        });
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
