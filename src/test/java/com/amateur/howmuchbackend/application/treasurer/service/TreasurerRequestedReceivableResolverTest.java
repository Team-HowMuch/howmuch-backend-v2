package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort.TreasurerSettlementRecord;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 총무 선택 대기 품목 정산 받을 금액")
class TreasurerRequestedReceivableResolverTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("5f0c1d7e-3b1a-4e7c-9a55-0a6f1a3d9c11");
    private static final UUID EXPENSE_ID = UUID.fromString("a2f3b4c5-d6e7-4f80-9123-456789abcdef");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("243a9c71-bf82-4678-87fa-f752d824ba37");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final Long CREATOR_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private SettlementItemAllocationStorePort itemAllocationStorePort;

    private TreasurerRequestedReceivableResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TreasurerRequestedReceivableResolver(
                loadSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementItemPort,
                loadSettlementExpensePort,
                itemAllocationStorePort,
                new SettlementItemAllocationPolicy()
        );
    }

    @Test
    @DisplayName("영수증 총액에서 생성자가 고른 몫을 뺀 금액을 받을 금액으로 계산한다")
    void resolve_creatorPickedItem_excludesCreatorShare() {
        SettlementItem pizza = item("피자", 10_000);
        SettlementItem cola = item("콜라", 2_000);
        givenSettlement(12_000, 0, List.of(pizza, cola), List.of(
                selection(cola, CREATOR_PARTICIPANT_ID),
                selection(pizza, PAYER_PARTICIPANT_ID)
        ));

        assertThat(resolver.resolve(SETTLEMENT_ID)).contains(10_000L);
    }

    @Test
    @DisplayName("생성자가 아무것도 고르지 않았으면 영수증 총액을 받을 금액으로 계산한다")
    void resolve_creatorPickedNothing_returnsReceiptTotal() {
        SettlementItem pizza = item("피자", 10_000);
        givenSettlement(10_000, 0, List.of(pizza), List.of());

        assertThat(resolver.resolve(SETTLEMENT_ID)).contains(10_000L);
    }

    @Test
    @DisplayName("영수증 할인은 정산 상세와 같은 방식으로 생성자 몫에 반영한다")
    void resolve_receiptDiscount_appliesToCreatorShare() {
        SettlementItem pizza = item("피자", 10_000);
        SettlementItem cola = item("콜라", 2_000);
        givenSettlement(10_800, 1_200, List.of(pizza, cola), List.of(selection(cola, CREATOR_PARTICIPANT_ID)));

        assertThat(resolver.resolve(SETTLEMENT_ID)).contains(9_000L);
    }

    @Test
    @DisplayName("정산을 찾을 수 없으면 비어 있고 다른 조회를 하지 않는다")
    void resolve_settlementMissing_returnsEmpty() {
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(SETTLEMENT_ID)).isEmpty();

        verifyNoInteractions(
                loadSettlementParticipantPort, loadSettlementItemPort, loadSettlementExpensePort, itemAllocationStorePort
        );
    }

    @Test
    @DisplayName("생성자가 참여자에 없으면 영수증 총액을 받을 금액으로 보고 품목은 조회하지 않는다")
    void resolve_creatorNotParticipant_returnsTotalWithoutLoadingItems() {
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(new Settlement(
                SETTLEMENT_ID, CREATOR_ID, "영수증 정산", 12_000,
                SettlementAllocationType.ITEM_ALLOCATION, 10L, SettlementStatus.REQUESTED, NOW, NOW
        )));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementParticipant(
                        PAYER_PARTICIPANT_ID, SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                        PAYER_ID, null, null, "참여자", 1, NOW, NOW
                )
        ));

        assertThat(resolver.resolve(SETTLEMENT_ID)).contains(12_000L);

        verifyNoInteractions(loadSettlementItemPort, loadSettlementExpensePort, itemAllocationStorePort);
    }

    @Test
    @DisplayName("배달비가 있으면 생성자 몫의 배달비도 뺀 금액을 돌려준다")
    void resolve_deliveryFee_excludesCreatorDeliveryShare() {
        SettlementItem pizza = item("피자", 10_000);
        SettlementItem cola = item("콜라", 2_000);
        givenSettlement(13_200, 0, 1_200, List.of(pizza, cola), List.of(selection(cola, CREATOR_PARTICIPANT_ID)));

        assertThat(resolver.resolve(SETTLEMENT_ID)).contains(11_000L);
    }

    @Test
    @DisplayName("저장된 선택이 품목과 맞지 않아도 예외를 던지지 않고 비어 있다")
    void resolve_inconsistentSelection_returnsEmptyInsteadOfFailing() {
        SettlementItem pizza = item("피자", 10_000);
        SettlementItem removed = item("삭제된 품목", 1_000);
        givenSettlement(10_000, 0, List.of(pizza), List.of(selection(removed, CREATOR_PARTICIPANT_ID)));

        assertThat(resolver.resolve(SETTLEMENT_ID)).isEmpty();
    }

    @Test
    @DisplayName("선택 대기 중인 품목별 받을 정산에만 적용한다")
    void appliesTo_onlyRequestedItemAllocationReceive() {
        assertThat(resolver.appliesTo(record(
                SettlementDirection.RECEIVE, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION
        ))).isTrue();

        assertThat(resolver.appliesTo(record(
                SettlementDirection.SEND, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION
        ))).isFalse();
        assertThat(resolver.appliesTo(record(
                SettlementDirection.RECEIVE, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.ITEM_ALLOCATION
        ))).isFalse();
        assertThat(resolver.appliesTo(record(
                SettlementDirection.RECEIVE, SettlementStatus.REQUESTED, SettlementAllocationType.DUTCH_PAY
        ))).isFalse();
        assertThat(resolver.appliesTo(record(
                SettlementDirection.RECEIVE, SettlementStatus.REQUESTED, null
        ))).isFalse();
    }

    private void givenSettlement(
            long totalAmount,
            long receiptDiscount,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections
    ) {
        givenSettlement(totalAmount, receiptDiscount, 0, items, selections);
    }

    private void givenSettlement(
            long totalAmount,
            long receiptDiscount,
            long deliveryFee,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections
    ) {
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(new Settlement(
                SETTLEMENT_ID, CREATOR_ID, "영수증 정산", totalAmount,
                SettlementAllocationType.ITEM_ALLOCATION, 10L, SettlementStatus.REQUESTED, NOW, NOW
        )));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementParticipant(
                        CREATOR_PARTICIPANT_ID, SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                        CREATOR_ID, null, null, "생성자", 0, NOW, NOW
                ),
                new SettlementParticipant(
                        PAYER_PARTICIPANT_ID, SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                        PAYER_ID, null, null, "참여자", 1, NOW, NOW
                )
        ));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementExpense.receiptOcr(
                        SETTLEMENT_ID, "receipt-1", "상호", totalAmount, null, 0, NOW, receiptDiscount, deliveryFee)
        ));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(items);
        when(itemAllocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(selections);
    }

    private SettlementItem item(String name, long amount) {
        return new SettlementItem(UUID.randomUUID(), SETTLEMENT_ID, EXPENSE_ID, name, 1, amount, 0, NOW, NOW);
    }

    private SettlementItemSelection selection(SettlementItem item, UUID participantId) {
        return new SettlementItemSelection(
                UUID.randomUUID(), SETTLEMENT_ID, item.id(), participantId,
                SettlementItemAllocationFraction.ONE, 1, NOW, NOW
        );
    }

    private TreasurerSettlementRecord record(
            SettlementDirection direction,
            SettlementStatus status,
            SettlementAllocationType allocationType
    ) {
        return new TreasurerSettlementRecord(
                SETTLEMENT_ID, "영수증 정산", direction, status, NOW, 0L, "나", 2, 0, allocationType
        );
    }
}
