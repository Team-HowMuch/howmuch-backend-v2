package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort.RequestedItemReceivableFacts;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("선택 대기 품목 정산 받을 금액 일괄 계산")
class RequestedItemReceivableCalculatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 0, 0);
    private static final UUID SETTLEMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_SETTLEMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXPENSE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private LoadRequestedItemReceivableFactsPort factsPort;

    @ParameterizedTest
    @CsvSource(value = {
            "ITEM_ALLOCATION, REQUESTED, true",
            "ITEM_ALLOCATION, SUSPENDED, true",
            "ITEM_ALLOCATION, AWAITING_DEPOSIT, false",
            "ITEM_ALLOCATION, COMPLETED, false",
            "ITEM_ALLOCATION, DRAFT, false",
            "DUTCH_PAY, REQUESTED, false",
            "DUTCH_PAY, SUSPENDED, false",
            "null, REQUESTED, false"
    }, nullValues = "null")
    @DisplayName("품목 정산의 요청·중단 상태에만 적용한다")
    void appliesTo_onlyRequestedOrSuspendedItemAllocation(
            SettlementAllocationType allocationType,
            SettlementStatus status,
            boolean expected
    ) {
        assertThat(calculator().appliesTo(allocationType, status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("영수증 총액에서 생성자가 고른 몫을 뺀다")
    void calculate_creatorPick_excluded() {
        SettlementItem pizza = item(SETTLEMENT_ID, "피자", 10_000, 0);
        SettlementItem cola = item(SETTLEMENT_ID, "콜라", 2_000, 1);
        when(factsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID))).thenReturn(List.of(facts(
                SETTLEMENT_ID, 12_000L, CREATOR_PARTICIPANT_ID, List.of(pizza, cola),
                List.of(selection(SETTLEMENT_ID, cola)), List.of(expense(SETTLEMENT_ID, 12_000L, 0, 0))
        )));

        assertThat(calculator().calculate(List.of(SETTLEMENT_ID))).containsExactlyEntriesOf(
                java.util.Map.of(SETTLEMENT_ID, 10_000L));
    }

    @Test
    @DisplayName("영수증 할인과 배달비 중 생성자 몫도 반영한다")
    void calculate_discountAndDeliveryFee_reflected() {
        SettlementItem pizza = item(SETTLEMENT_ID, "피자", 10_000, 0);
        SettlementItem cola = item(SETTLEMENT_ID, "콜라", 2_000, 1);
        // 총액 12,000 − 할인 1,200 + 배달비 1,200 = 12,000.
        // 생성자 몫 = 콜라 2,000 − 할인 200 + 배달비 200 = 2,000 → 받을 금액 10,000.
        when(factsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID))).thenReturn(List.of(facts(
                SETTLEMENT_ID, 12_000L, CREATOR_PARTICIPANT_ID, List.of(pizza, cola),
                List.of(selection(SETTLEMENT_ID, cola)), List.of(expense(SETTLEMENT_ID, 12_000L, 1_200, 1_200))
        )));

        assertThat(calculator().calculate(List.of(SETTLEMENT_ID))).containsEntry(SETTLEMENT_ID, 10_000L);
    }

    @Test
    @DisplayName("생성자가 참여자가 아니면 뺄 몫이 없어 총액을 쓴다")
    void calculate_creatorNotParticipant_usesTotal() {
        when(factsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID))).thenReturn(List.of(facts(
                SETTLEMENT_ID, 12_000L, null, List.of(item(SETTLEMENT_ID, "피자", 12_000, 0)), List.of(), List.of()
        )));

        assertThat(calculator().calculate(List.of(SETTLEMENT_ID))).containsEntry(SETTLEMENT_ID, 12_000L);
    }

    @Test
    @DisplayName("저장된 값이 맞지 않는 정산 한 건은 빼고 나머지는 계산한다")
    void calculate_inconsistentSettlement_skippedOthersKept() {
        SettlementItem removed = item(SETTLEMENT_ID, "삭제된 품목", 1_000, 0);
        SettlementItem pizza = item(OTHER_SETTLEMENT_ID, "피자", 10_000, 0);
        when(factsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID, OTHER_SETTLEMENT_ID))).thenReturn(List.of(
                facts(SETTLEMENT_ID, 10_000L, CREATOR_PARTICIPANT_ID, List.of(),
                        List.of(selection(SETTLEMENT_ID, removed)), List.of()),
                facts(OTHER_SETTLEMENT_ID, 10_000L, CREATOR_PARTICIPANT_ID, List.of(pizza), List.of(), List.of())
        ));

        var result = calculator().calculate(List.of(SETTLEMENT_ID, OTHER_SETTLEMENT_ID));

        assertThat(result).doesNotContainKey(SETTLEMENT_ID).containsEntry(OTHER_SETTLEMENT_ID, 10_000L);
    }

    @Test
    @DisplayName("계산할 정산이 없으면 조회하지 않는다")
    void calculate_empty_doesNotLoad() {
        assertThat(calculator().calculate(List.of())).isEmpty();
        assertThat(calculator().calculate(null)).isEmpty();
        verifyNoInteractions(factsPort);
    }

    private RequestedItemReceivableCalculator calculator() {
        return new RequestedItemReceivableCalculator(factsPort, new SettlementItemAllocationPolicy());
    }

    private static RequestedItemReceivableFacts facts(
            UUID settlementId,
            long totalAmount,
            UUID creatorParticipantId,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            List<SettlementExpense> expenses
    ) {
        return new RequestedItemReceivableFacts(
                settlementId, totalAmount, creatorParticipantId, items, selections, expenses);
    }

    private static SettlementItem item(UUID settlementId, String name, long amount, int displayOrder) {
        return new SettlementItem(UUID.randomUUID(), settlementId, EXPENSE_ID, name, 1, amount, displayOrder, NOW, NOW);
    }

    private static SettlementItemSelection selection(UUID settlementId, SettlementItem item) {
        return new SettlementItemSelection(UUID.randomUUID(), settlementId, item.id(), CREATOR_PARTICIPANT_ID,
                SettlementItemAllocationFraction.ONE, 1, NOW, NOW);
    }

    private static SettlementExpense expense(UUID settlementId, long amount, long discount, long deliveryFee) {
        return SettlementExpense.receiptOcr(settlementId, "receipt-1", "상호", amount, null, 0, NOW, discount, deliveryFee);
    }
}
