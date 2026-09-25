package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
@DisplayName("전체 할인 영수증의 품목 제출")
class SettlementDiscountedAllocationWriterTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID FIRST_ID = UUID.randomUUID();
    private static final UUID SECOND_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 0, 0);

    @Mock private SettlementItemAllocationStateLoader stateLoader;
    @Mock private LoadSettlementParticipantPort participantPort;
    @Mock private SettlementItemAllocationStorePort allocationStore;
    @Mock private SaveSettlementSharePort sharePort;
    @Mock private SaveSettlementPort settlementPort;

    private SettlementItemAllocationWriter writer;
    private SettlementItem item;
    private SettlementItemSelection selection;

    @BeforeEach
    void setUp() {
        writer = new SettlementItemAllocationWriter(
                stateLoader, participantPort, allocationStore, sharePort,
                settlementPort, new SettlementItemAllocationPolicy()
        );
        item = SettlementItem.fromReceipt(
                SETTLEMENT_ID, UUID.randomUUID(), "커피", 1, 1_000, 0, NOW
        );
        selection = SettlementItemSelection.create(
                SETTLEMENT_ID, item.id(), FIRST_ID,
                SettlementItemAllocationFraction.ONE, 1, NOW
        );
    }

    @Test
    @DisplayName("첫 제출에는 할인 나머지가 확정되지 않아 Share를 만들지 않는다")
    void discountedReceipt_firstSubmission_waitsForOthers() {
        arrangeState(FIRST_ID);
        when(allocationStore.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(selection));
        when(participantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());
        when(allocationStore.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(List.of(SettlementItemSubmission.submit(SETTLEMENT_ID, FIRST_ID, NOW)));

        writer.submit(SETTLEMENT_ID, FIRST_ID, NOW, SettlementItemAllocationEntry.LINK);

        verify(sharePort, never()).saveAll(any());
        verify(settlementPort, never()).update(any());
    }

    @Test
    @DisplayName("마지막 제출에는 정확한 할인 금액의 Share를 생성한다")
    void discountedReceipt_lastSubmission_finalizesShares() {
        arrangeState(SECOND_ID);
        when(allocationStore.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(selection));
        when(participantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());
        when(allocationStore.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItemSubmission.submit(SETTLEMENT_ID, FIRST_ID, NOW),
                SettlementItemSubmission.submit(SETTLEMENT_ID, SECOND_ID, NOW)
        ));

        writer.submit(SETTLEMENT_ID, SECOND_ID, NOW, SettlementItemAllocationEntry.LINK);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementShare>> shares = ArgumentCaptor.forClass(List.class);
        verify(sharePort).saveAll(shares.capture());
        assertThat(shares.getValue()).singleElement()
                .satisfies(share -> assertThat(share.amount()).isEqualTo(900));
        ArgumentCaptor<Settlement> settlement = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementPort).update(settlement.capture());
        assertThat(settlement.getValue().status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
    }

    private void arrangeState(UUID currentParticipantId) {
        Settlement settlement = new Settlement(
                SETTLEMENT_ID, 1L, "영수증", 900,
                SettlementAllocationType.ITEM_ALLOCATION, 7L,
                SettlementStatus.REQUESTED, NOW, NOW
        );
        when(stateLoader.load(SETTLEMENT_ID, currentParticipantId, true,
                SettlementItemAllocationEntry.LINK))
                .thenReturn(new SettlementItemAllocationStateLoader.State(
                        settlement,
                        currentParticipantId.equals(FIRST_ID)
                                ? participants().getFirst() : participants().get(1),
                        List.of(item), 100
                ));
        when(allocationStore.findSubmission(SETTLEMENT_ID, currentParticipantId))
                .thenReturn(Optional.empty());
    }

    private List<SettlementParticipant> participants() {
        return List.of(participant(FIRST_ID, 0), participant(SECOND_ID, 1));
    }

    private SettlementParticipant participant(UUID id, int order) {
        return new SettlementParticipant(
                id, SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                (long) order + 1, null, null, "참여자", order, NOW, NOW
        );
    }
}
