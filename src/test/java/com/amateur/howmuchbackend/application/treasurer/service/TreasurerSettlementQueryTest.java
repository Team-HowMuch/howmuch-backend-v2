package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreasurerSettlementQueryTest {
    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;

    @Test
    void mapsLoadedRecordsToPersonalSettlements() {
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
        TreasurerSettlementQuery query = new TreasurerSettlementQuery(currentActorPort, loadTreasurerSettlementsPort);

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
}
