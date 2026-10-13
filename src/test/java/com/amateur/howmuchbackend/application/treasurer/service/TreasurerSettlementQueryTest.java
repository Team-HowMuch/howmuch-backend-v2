package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreasurerSettlementQueryTest {
    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;

    @Mock
    private TreasurerRequestedReceivableResolver requestedReceivableResolver;

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
        TreasurerSettlementQuery query = new TreasurerSettlementQuery(
                currentActorPort, loadTreasurerSettlementsPort, requestedReceivableResolver
        );

        var result = query.loadMine();

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
    }

    @Test
    @DisplayName("선택 대기 중인 영수증 정산의 받을 금액은 share 합계 대신 정산 상세와 같은 기준으로 바꾼다")
    void settlementQuery_requestedItemAllocationReceive_usesResolvedAmount() {
        UUID receiveId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        var record = receiptRecord(receiveId, SettlementDirection.RECEIVE, 0L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L)).thenReturn(List.of(record));
        when(requestedReceivableResolver.appliesTo(record)).thenReturn(true);
        when(requestedReceivableResolver.resolve(receiveId)).thenReturn(Optional.of(10_000L));
        TreasurerSettlementQuery query = new TreasurerSettlementQuery(
                currentActorPort, loadTreasurerSettlementsPort, requestedReceivableResolver
        );

        var result = query.loadMine();

        assertThat(result).singleElement().satisfies(settlement -> assertThat(settlement.amount()).isEqualTo(10_000L));
        assertThat(TreasurerSummary.from(result).receiveAmount()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("받을 금액을 계산하지 못하면 조회한 금액을 그대로 쓴다")
    void settlementQuery_resolverEmpty_keepsLoadedAmount() {
        UUID receiveId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var record = receiptRecord(receiveId, SettlementDirection.RECEIVE, 0L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L)).thenReturn(List.of(record));
        when(requestedReceivableResolver.appliesTo(record)).thenReturn(true);
        when(requestedReceivableResolver.resolve(receiveId)).thenReturn(Optional.empty());
        TreasurerSettlementQuery query = new TreasurerSettlementQuery(
                currentActorPort, loadTreasurerSettlementsPort, requestedReceivableResolver
        );

        assertThat(query.loadMine()).singleElement()
                .satisfies(settlement -> assertThat(settlement.amount()).isZero());
    }

    @Test
    @DisplayName("대상이 아닌 정산은 추가 조회 없이 조회한 금액을 그대로 쓴다")
    void settlementQuery_notApplicable_doesNotResolve() {
        UUID sendId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        var record = receiptRecord(sendId, SettlementDirection.SEND, 4_000L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(7L));
        when(loadTreasurerSettlementsPort.findAll(7L)).thenReturn(List.of(record));
        TreasurerSettlementQuery query = new TreasurerSettlementQuery(
                currentActorPort, loadTreasurerSettlementsPort, requestedReceivableResolver
        );

        assertThat(query.loadMine()).singleElement()
                .satisfies(settlement -> assertThat(settlement.amount()).isEqualTo(4_000L));

        verify(requestedReceivableResolver, never()).resolve(any());
    }

    private LoadTreasurerSettlementsPort.TreasurerSettlementRecord receiptRecord(
            UUID settlementId,
            SettlementDirection direction,
            Long amount
    ) {
        return new LoadTreasurerSettlementsPort.TreasurerSettlementRecord(
                settlementId,
                "영수증 정산",
                direction,
                SettlementStatus.REQUESTED,
                LocalDateTime.of(2026, 10, 12, 0, 0),
                amount,
                "나",
                2,
                0,
                SettlementAllocationType.ITEM_ALLOCATION
        );
    }
}
