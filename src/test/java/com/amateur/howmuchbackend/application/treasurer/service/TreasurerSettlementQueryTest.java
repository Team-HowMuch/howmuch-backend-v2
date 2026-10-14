package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort.RequestedItemReceivableFacts;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.application.settlement.service.RequestedItemReceivableCalculator;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreasurerSettlementQueryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 0, 0);
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EXPENSE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;

    @Mock
    private LoadRequestedItemReceivableFactsPort loadRequestedItemReceivableFactsPort;

    @Test
    void settlementQuery_loadedRecords_mappedToPersonalSettlements() {
        UUID payId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID receiveId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L)).thenReturn(List.of(
                new LoadTreasurerSettlementsPort.TreasurerSettlementRecord(
                        payId,
                        "낼 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        5_500L,
                        "요청자",
                        1,
                        0,
                        SettlementAllocationType.ITEM_ALLOCATION
                ),
                new LoadTreasurerSettlementsPort.TreasurerSettlementRecord(
                        receiveId,
                        "받을 정산",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 11, 0, 0),
                        6_000L,
                        "나",
                        1,
                        0,
                        SettlementAllocationType.DUTCH_PAY
                )
        ));

        var result = query().loadMine();

        assertThat(result).satisfiesExactly(
                send -> {
                    assertThat(send.settlementId()).isEqualTo(payId);
                    assertThat(send.direction()).isEqualTo(SettlementDirection.SEND);
                    assertThat(send.amount()).isEqualTo(5_500L);
                    assertThat(send.allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION);
                },
                receive -> {
                    assertThat(receive.settlementId()).isEqualTo(receiveId);
                    assertThat(receive.direction()).isEqualTo(SettlementDirection.RECEIVE);
                    assertThat(receive.amount()).isEqualTo(6_000L);
                    assertThat(receive.allocationType()).isEqualTo(SettlementAllocationType.DUTCH_PAY);
                }
        );
        verifyNoInteractions(loadRequestedItemReceivableFactsPort);
    }

    @Test
    @DisplayName("선택 대기 중인 영수증 정산의 받을 금액은 share 합계 대신 정산 상세와 같은 기준으로 바꾼다")
    void settlementQuery_requestedItemAllocationReceive_usesCalculatedAmount() {
        UUID receiveId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L))
                .thenReturn(List.of(receiptRecord(receiveId, SettlementDirection.RECEIVE, SettlementStatus.REQUESTED, 0L)));
        when(loadRequestedItemReceivableFactsPort.findAllBySettlementIds(List.of(receiveId)))
                .thenReturn(List.of(creatorPickedCola(receiveId)));

        var result = query().loadMine();

        assertThat(result).singleElement().satisfies(settlement -> assertThat(settlement.amount()).isEqualTo(10_000L));
        assertThat(TreasurerSummary.from(result).receiveAmount()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("받을 금액을 계산하지 못하면 조회한 금액을 그대로 쓴다")
    void settlementQuery_factsMissing_keepsLoadedAmount() {
        UUID receiveId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L))
                .thenReturn(List.of(receiptRecord(receiveId, SettlementDirection.RECEIVE, SettlementStatus.REQUESTED, 0L)));
        when(loadRequestedItemReceivableFactsPort.findAllBySettlementIds(List.of(receiveId))).thenReturn(List.of());

        assertThat(query().loadMine()).singleElement()
                .satisfies(settlement -> assertThat(settlement.amount()).isZero());
    }

    @Test
    @DisplayName("대상이 아닌 정산은 추가 조회 없이 조회한 금액을 그대로 쓴다")
    void settlementQuery_notApplicable_doesNotLoadFacts() {
        UUID sendId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L))
                .thenReturn(List.of(receiptRecord(sendId, SettlementDirection.SEND, SettlementStatus.REQUESTED, 4_000L)));

        assertThat(query().loadMine()).singleElement()
                .satisfies(settlement -> assertThat(settlement.amount()).isEqualTo(4_000L));
        verifyNoInteractions(loadRequestedItemReceivableFactsPort);
    }

    private TreasurerSettlementQuery query() {
        return new TreasurerSettlementQuery(
                currentActorPort,
                loadTreasurerSettlementsPort,
                new RequestedItemReceivableCalculator(
                        loadRequestedItemReceivableFactsPort, new SettlementItemAllocationPolicy())
        );
    }

    /** 총액 12,000원 중 생성자가 콜라 2,000원을 골랐다. 받을 금액은 10,000원이다. */
    static RequestedItemReceivableFacts creatorPickedCola(UUID settlementId) {
        SettlementItem pizza = new SettlementItem(UUID.randomUUID(), settlementId, EXPENSE_ID, "피자", 1, 10_000, 0, NOW, NOW);
        SettlementItem cola = new SettlementItem(UUID.randomUUID(), settlementId, EXPENSE_ID, "콜라", 1, 2_000, 1, NOW, NOW);
        return new RequestedItemReceivableFacts(
                settlementId,
                12_000L,
                CREATOR_PARTICIPANT_ID,
                List.of(pizza, cola),
                List.of(new SettlementItemSelection(UUID.randomUUID(), settlementId, cola.id(), CREATOR_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.ONE, 1, NOW, NOW)),
                List.of(SettlementExpense.receiptOcr(settlementId, "receipt-1", "상호", 12_000L, null, 0, NOW))
        );
    }

    private LoadTreasurerSettlementsPort.TreasurerSettlementRecord receiptRecord(
            UUID settlementId,
            SettlementDirection direction,
            SettlementStatus status,
            Long amount
    ) {
        return new LoadTreasurerSettlementsPort.TreasurerSettlementRecord(
                settlementId,
                "영수증 정산",
                direction,
                status,
                LocalDateTime.of(2026, 10, 12, 0, 0),
                amount,
                "나",
                2,
                0,
                SettlementAllocationType.ITEM_ALLOCATION
        );
    }
}
