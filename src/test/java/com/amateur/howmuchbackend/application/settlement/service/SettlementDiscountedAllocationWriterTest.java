package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
@DisplayName("전체 할인·배달비 영수증의 품목 제출")
class SettlementDiscountedAllocationWriterTest {
    @Mock private SettlementRevisionGuard revisionGuard;
    @Mock private DeleteSettlementSharePort deleteSettlementSharePort;
    @org.mockito.Mock private NettingEstimateInvalidationService estimateInvalidation;
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
                settlementPort, new SettlementItemAllocationPolicy(),
                estimateInvalidation,
                revisionGuard,
                deleteSettlementSharePort
        );
        item = SettlementItem.fromReceipt(
                SETTLEMENT_ID, UUID.randomUUID(), "커피", 1, 1_000, 0, NOW
        );
        selection = SettlementItemSelection.create(
                SETTLEMENT_ID, item.id(), FIRST_ID,
                SettlementItemAllocationFraction.ONE, 1, NOW
        );
    }

    @ParameterizedTest
    @CsvSource({"100, 0", "0, 3000", "100, 3000"})
    @DisplayName("첫 제출에는 할인·배달비 나머지가 확정되지 않아 Share를 만들지 않는다")
    void discountedReceipt_firstSubmission_waitsForOthers(long discount, long deliveryFee) {
        arrangeState(FIRST_ID, discount, deliveryFee);
        when(allocationStore.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(selection));
        when(participantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());
        when(allocationStore.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(List.of(SettlementItemSubmission.submit(SETTLEMENT_ID, FIRST_ID, NOW)));

        writer.submit(SETTLEMENT_ID, FIRST_ID, NOW, SettlementItemAllocationEntry.LINK);

        verify(sharePort, never()).saveAll(any());
        verify(settlementPort, never()).update(any());
    }

    @ParameterizedTest
    @CsvSource({"100, 0", "0, 3000", "100, 3000"})
    @DisplayName("마지막 제출에는 할인·배달비를 반영한 Share를 생성한다")
    void discountedReceipt_lastSubmission_finalizesShares(long discount, long deliveryFee) {
        arrangeState(SECOND_ID, discount, deliveryFee);
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
                .satisfies(share -> assertThat(share.amount()).isEqualTo(1_000 - discount + deliveryFee));
        ArgumentCaptor<Settlement> settlement = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementPort).update(settlement.capture());
        assertThat(settlement.getValue().status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        verify(estimateInvalidation).settlementChanged(SETTLEMENT_ID);

    }

    @Test
    @DisplayName("배달비가 있는 정산은 전원 제출해도 미배분 품목이 남으면 Share 없이 중단한다")
    void deliveryReceipt_incompleteAllocation_suspendsWithoutShares() {
        arrangeState(SECOND_ID, 0, 3_000);
        when(allocationStore.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of());
        when(participantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());
        when(allocationStore.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItemSubmission.submit(SETTLEMENT_ID, FIRST_ID, NOW),
                SettlementItemSubmission.submit(SETTLEMENT_ID, SECOND_ID, NOW)));

        writer.submit(SETTLEMENT_ID, SECOND_ID, NOW, SettlementItemAllocationEntry.LINK);

        verify(sharePort, never()).saveAll(any());
        var captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementPort).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SettlementStatus.SUSPENDED);
    }

    private void arrangeState(UUID currentParticipantId, long discount, long deliveryFee) {
        Settlement settlement = new Settlement(
                SETTLEMENT_ID, 1L, "영수증", 1_000 - discount + deliveryFee,
                SettlementAllocationType.ITEM_ALLOCATION, 7L,
                SettlementStatus.REQUESTED, NOW, NOW
        );
        when(stateLoader.load(SETTLEMENT_ID, currentParticipantId, true,
                SettlementItemAllocationEntry.LINK))
                .thenReturn(new SettlementItemAllocationStateLoader.State(
                        settlement,
                        currentParticipantId.equals(FIRST_ID)
                                ? participants().getFirst() : participants().get(1),
                        List.of(item), discount, deliveryFee
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
