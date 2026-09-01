package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 품목 배분 Application 흐름")
class SettlementItemAllocationServiceTest {
    private static final String RAW_TOKEN = "x".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final UUID EXPENSE_ID = UUID.randomUUID();
    private static final UUID FIRST_PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID SECOND_PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID CONTEXT_ID = UUID.randomUUID();
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Mock private SettlementLinkTokenPort linkTokenPort;
    @Mock private LoadSettlementLinkPort loadSettlementLinkPort;
    @Mock private LoadSettlementParticipantContextPort loadParticipantContextPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private SettlementItemAllocationStorePort allocationStorePort;
    @Mock private SaveSettlementSharePort saveSettlementSharePort;

    private SettlementItemAllocationService service;

    @BeforeEach
    void setUp() {
        SettlementItemAllocationPolicy policy = new SettlementItemAllocationPolicy();
        SettlementItemAllocationStateLoader stateLoader = new SettlementItemAllocationStateLoader(
                loadSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementItemPort
        );
        SettlementItemAllocationWriter writer = new SettlementItemAllocationWriter(
                stateLoader,
                loadSettlementParticipantPort,
                allocationStorePort,
                saveSettlementSharePort,
                saveSettlementPort,
                policy
        );
        SettlementItemAllocationReader reader = new SettlementItemAllocationReader(
                stateLoader,
                loadSettlementParticipantPort,
                allocationStorePort,
                policy
        );
        service = new SettlementItemAllocationService(
                linkTokenPort,
                loadSettlementLinkPort,
                loadParticipantContextPort,
                writer,
                reader
        );
    }

    @Test
    @DisplayName("제출하면 남을 기다리지 않고 내 Share부터 만든다")
    void createsMyShareBeforeOthersSubmit() {
        arrangeRequestedAccess();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.REQUESTED)));
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, FIRST_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(exactSelections());
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementItemSubmission(
                        UUID.randomUUID(), SETTLEMENT_ID, FIRST_PARTICIPANT_ID, CREATED_AT
                )
        ));

        service.submit(RAW_TOKEN, CONTEXT_ID, FIRST_PARTICIPANT_ID);

        ArgumentCaptor<List<SettlementShare>> shares = ArgumentCaptor.forClass(List.class);
        verify(saveSettlementSharePort).saveAll(shares.capture());
        assertThat(shares.getValue())
                .extracting(SettlementShare::payerParticipantId)
                .containsExactly(FIRST_PARTICIPANT_ID);
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("마지막 제출에서 품목 배분이 정확하면 입금 대기로 전환한다")
    void finalizesSharesOnLastExactSubmission() {
        arrangeRequestedAccess();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        List<SettlementItemSelection> selections = exactSelections();
        List<SettlementItemSubmission> submissions = allSubmissions();
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, FIRST_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(selections);
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(submissions);
        when(saveSettlementPort.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.submit(RAW_TOKEN, CONTEXT_ID, FIRST_PARTICIPANT_ID);

        assertThat(result.phase().name()).isEqualTo("TRANSFER_READY");
        assertThat(result.personalAmount()).isEqualTo(500);
        ArgumentCaptor<List<SettlementShare>> shares = ArgumentCaptor.forClass(List.class);
        verify(saveSettlementSharePort).saveAll(shares.capture());
        assertThat(shares.getValue()).extracting(SettlementShare::amount)
                .containsExactly(500L);
        ArgumentCaptor<Settlement> settlement = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(settlement.capture());
        assertThat(settlement.getValue().status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
    }

    @Test
    @DisplayName("마지막 제출 후 미배분 품목이 남으면 중단한다. 이미 만든 내 Share는 남는다")
    void suspendsWhenAllocationIsIncomplete() {
        arrangeRequestedAccess();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.SUSPENDED)));
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, FIRST_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        ));
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(allSubmissions());
        when(saveSettlementPort.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.submit(RAW_TOKEN, CONTEXT_ID, FIRST_PARTICIPANT_ID);

        assertThat(result.phase().name()).isEqualTo("SUSPENDED");
        assertThat(result.invalidItemIds()).containsExactly(ITEM_ID);
        ArgumentCaptor<Settlement> settlement = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(settlement.capture());
        assertThat(settlement.getValue().status()).isEqualTo(SettlementStatus.SUSPENDED);
    }

    @Test
    @DisplayName("중단된 정산을 다시 조회해도 미배분 품목을 반환한다")
    void returnsInvalidItemsWhenSuspendedSettlementIsReloaded() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                context(FIRST_PARTICIPANT_ID)
        ));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.SUSPENDED)));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID, FIRST_PARTICIPANT_ID
        )).thenReturn(Optional.of(participants().getFirst()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(item()));
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        ));
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(allSubmissions());

        var result = service.get(RAW_TOKEN, CONTEXT_ID, FIRST_PARTICIPANT_ID);

        assertThat(result.phase().name()).isEqualTo("SUSPENDED");
        assertThat(result.invalidItemIds()).containsExactly(ITEM_ID);
    }

    @Test
    @DisplayName("헤더 문맥의 참여자와 path 참여자가 다르면 정산을 조회하지 않는다")
    void rejectsMismatchedParticipantContext() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                context(SECOND_PARTICIPANT_ID)
        ));

        assertThatThrownBy(() -> service.get(
                RAW_TOKEN,
                CONTEXT_ID,
                FIRST_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                ));
        verify(loadSettlementPort, never()).findById(any());
    }

    @Test
    @DisplayName("완료된 정산은 남아 있는 참여 문맥으로 품목 화면을 다시 열 수 없다")
    void rejectsCompletedSettlementAccess() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                context(FIRST_PARTICIPANT_ID)
        ));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.COMPLETED)));

        assertThatThrownBy(() -> service.get(
                RAW_TOKEN,
                CONTEXT_ID,
                FIRST_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        verify(loadSettlementParticipantPort, never())
                .findBySettlementIdAndId(any(), any());
    }

    private void arrangeRequestedAccess() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                context(FIRST_PARTICIPANT_ID)
        ));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.REQUESTED)));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID, FIRST_PARTICIPANT_ID
        )).thenReturn(Optional.of(participants().getFirst()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(item()));
    }

    private void arrangeLink() {
        when(linkTokenPort.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(loadSettlementLinkPort.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(
                new SettlementLink(
                        UUID.randomUUID(),
                        SETTLEMENT_ID,
                        TOKEN_HASH,
                        SettlementLinkStatus.ACTIVE,
                        LocalDateTime.of(2099, 1, 1, 0, 0),
                        CREATED_AT,
                        CREATED_AT
                )
        ));
    }

    private SettlementParticipantContext context(UUID participantId) {
        return new SettlementParticipantContext(
                CONTEXT_ID,
                TOKEN_HASH,
                SETTLEMENT_ID,
                participantId,
                CREATED_AT,
                LocalDateTime.of(2099, 1, 1, 0, 0)
        );
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                1_000,
                SettlementAllocationType.ITEM_ALLOCATION,
                7L,
                status,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementItem item() {
        return new SettlementItem(
                ITEM_ID,
                SETTLEMENT_ID,
                EXPENSE_ID,
                "피자",
                1,
                1_000,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(FIRST_PARTICIPANT_ID, 1L, "최수아", 0),
                participant(SECOND_PARTICIPANT_ID, 2L, "민선영", 1)
        );
    }

    private SettlementParticipant participant(
            UUID id,
            Long linkedUserId,
            String name,
            int order
    ) {
        return new SettlementParticipant(
                id,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                linkedUserId,
                null,
                null,
                name,
                order,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementItemSelection> exactSelections() {
        return List.of(
                selection(FIRST_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                selection(SECOND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        );
    }

    private SettlementItemSelection selection(
            UUID participantId,
            SettlementItemAllocationFraction fraction
    ) {
        return new SettlementItemSelection(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                ITEM_ID,
                participantId,
                fraction,
                1,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementItemSubmission> allSubmissions() {
        return List.of(
                new SettlementItemSubmission(
                        UUID.randomUUID(), SETTLEMENT_ID, FIRST_PARTICIPANT_ID, CREATED_AT
                ),
                new SettlementItemSubmission(
                        UUID.randomUUID(), SETTLEMENT_ID, SECOND_PARTICIPANT_ID, CREATED_AT
                )
        );
    }
}
