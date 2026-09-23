package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("생성자 품목 몫 선택 Application 흐름")
class SettlementCreatorItemAllocationServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final Long OTHER_USER_ID = 99L;
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final UUID EXPENSE_ID = UUID.randomUUID();
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID FRIEND_PARTICIPANT_ID = UUID.randomUUID();
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private SettlementItemAllocationStorePort allocationStorePort;
    @Mock private SaveSettlementSharePort saveSettlementSharePort;

    private SettlementCreatorItemAllocationService service;

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
        service = new SettlementCreatorItemAllocationService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementParticipantPort,
                writer,
                reader
        );
    }

    @Test
    @DisplayName("알림 발송 전 DRAFT에서도 생성자가 자기 품목을 조회한다")
    void creatorItemSelection_draft_returnsOwnItems() {
        arrangeCreatorAccess(SettlementStatus.DRAFT);
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of());
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of());

        var result = service.getMyItems(SETTLEMENT_ID);

        assertThat(result.participantId()).isEqualTo(CREATOR_PARTICIPANT_ID);
        assertThat(result.phase().name()).isEqualTo("SELECTING");
        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.itemId()).isEqualTo(ITEM_ID));
    }

    @Test
    @DisplayName("DRAFT에서 생성자가 자기 몫 비율을 저장한다")
    void creatorItemSelection_draft_savesOwnFraction() {
        arrangeCreatorAccess(SettlementStatus.DRAFT);
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, CREATOR_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of());
        when(allocationStorePort.findSelection(SETTLEMENT_ID, ITEM_ID, CREATOR_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of());

        service.saveMyItem(
                SETTLEMENT_ID,
                ITEM_ID,
                new SaveSettlementItemSelectionCommand(SettlementItemAllocationFraction.HALF, 1)
        );

        verify(allocationStorePort).saveSelection(any(SettlementItemSelection.class));
    }

    @Test
    @DisplayName("DRAFT에서는 전원이 제출해도 확정하지 않는다")
    void creatorItemAllocation_draft_neverFinalizes() {
        arrangeCreatorAccess(SettlementStatus.DRAFT);
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, CREATOR_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(allocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(exactSelections());
        when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(allSubmissions());

        var result = service.submitMyItems(SETTLEMENT_ID);

        verify(allocationStorePort).saveSubmission(any(SettlementItemSubmission.class));
        verify(saveSettlementSharePort, never()).saveAll(anyList());
        verify(saveSettlementPort, never()).update(any(Settlement.class));
        assertThat(result.phase().name()).isEqualTo("WAITING_FOR_OTHERS");
    }

    @Test
    @DisplayName("생성자가 아니면 접근을 거부한다")
    void creatorItemAllocation_nonCreator_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.DRAFT)));

        assertThatThrownBy(() -> service.getMyItems(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
    }

    @Test
    @DisplayName("이미 제출했으면 다시 고칠 수 없다")
    void rejectsEditAfterSubmission() {
        arrangeCreatorAccess(SettlementStatus.DRAFT);
        when(allocationStorePort.findSubmission(SETTLEMENT_ID, CREATOR_PARTICIPANT_ID))
                .thenReturn(Optional.of(SettlementItemSubmission.submit(
                        SETTLEMENT_ID,
                        CREATOR_PARTICIPANT_ID,
                        CREATED_AT
                )));

        assertThatThrownBy(() -> service.saveMyItem(
                SETTLEMENT_ID,
                ITEM_ID,
                new SaveSettlementItemSelectionCommand(SettlementItemAllocationFraction.HALF, 1)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("확정된 정산은 더 이상 고칠 수 없다")
    void rejectsEditAfterDeposit() {
        arrangeCreatorAccess(SettlementStatus.AWAITING_DEPOSIT);

        assertThatThrownBy(() -> service.removeMyItem(SETTLEMENT_ID, ITEM_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE));
    }

    private void arrangeCreatorAccess(SettlementStatus status) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        // 조회는 findById, 쓰기는 잠금 조회를 쓴다. 테스트마다 둘 중 하나만 타므로 lenient로 둔다.
        lenient().when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));
        lenient().when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                CREATOR_PARTICIPANT_ID
        )).thenReturn(Optional.of(participants().get(0)));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(item()));
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
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
                participant(CREATOR_PARTICIPANT_ID, CREATOR_ID, "최수아", 0),
                participant(FRIEND_PARTICIPANT_ID, 2L, "민선영", 1)
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
                selection(CREATOR_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF),
                selection(FRIEND_PARTICIPANT_ID, SettlementItemAllocationFraction.HALF)
        );
    }

    private SettlementItemSelection selection(
            UUID participantId,
            SettlementItemAllocationFraction fraction
    ) {
        return SettlementItemSelection.create(
                SETTLEMENT_ID,
                ITEM_ID,
                participantId,
                fraction,
                1,
                CREATED_AT
        );
    }

    private List<SettlementItemSubmission> allSubmissions() {
        return List.of(
                SettlementItemSubmission.submit(SETTLEMENT_ID, CREATOR_PARTICIPANT_ID, CREATED_AT),
                SettlementItemSubmission.submit(SETTLEMENT_ID, FRIEND_PARTICIPANT_ID, CREATED_AT)
        );
    }
}
