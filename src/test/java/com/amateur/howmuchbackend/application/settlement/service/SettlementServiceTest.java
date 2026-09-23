package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDeletionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 Application 흐름")
class SettlementServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 21, 9, 0);

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadSettlementPort loadSettlementPort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private DeleteSettlementPort deleteSettlementPort;

    @Mock
    private LoadSettlementSharePort loadSettlementSharePort;

    @Mock
    private SaveSettlementParticipantPort saveSettlementParticipantPort;

    @Mock
    private SettlementItemAllocationStorePort settlementItemAllocationStorePort;

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                currentActorPort,
                loadSettlementPort,
                saveSettlementPort,
                deleteSettlementPort,
                loadSettlementSharePort,
                saveSettlementParticipantPort,
                settlementItemAllocationStorePort,
                new SettlementDeletionPolicy(),
                loadUserPort,
                allocationDraftInvalidator
        );
        lenient().when(allocationDraftInvalidator.invalidate(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("요청 본문으로 생성자를 위조할 수 없도록 현재 로그인 사용자를 생성자로 사용한다")
    void settlement_currentActor_usedAsCreator() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadUserPort.findByIdForUpdate(CREATOR_ID)).thenReturn(Optional.of(creator()));
        when(saveSettlementPort.save(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(saveSettlementParticipantPort.save(any(SettlementParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = settlementService.create(new CreateSettlementCommand("회식 정산", 10_000));

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        ArgumentCaptor<SettlementParticipant> participantCaptor =
                ArgumentCaptor.forClass(SettlementParticipant.class);
        verify(saveSettlementPort).save(settlementCaptor.capture());
        verify(saveSettlementParticipantPort).save(participantCaptor.capture());
        assertThat(settlementCaptor.getValue().creatorId()).isEqualTo(CREATOR_ID);
        assertThat(participantCaptor.getValue().settlementId()).isEqualTo(settlementCaptor.getValue().id());
        assertThat(participantCaptor.getValue().source()).isEqualTo(SettlementParticipantSource.REGISTERED_USER);
        assertThat(participantCaptor.getValue().linkedUserId()).isEqualTo(CREATOR_ID);
        assertThat(participantCaptor.getValue().displayName()).isEqualTo("최수아");
        assertThat(participantCaptor.getValue().displayOrder()).isZero();
        assertThat(result.creatorId()).isEqualTo(CREATOR_ID);
        assertThat(result.status()).isEqualTo(SettlementStatus.DRAFT);
    }

    @Test
    @DisplayName("현재 사용자를 찾을 수 없으면 참여자 없는 정산을 만들지 않는다")
    void settlement_missingCurrentUser_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadUserPort.findByIdForUpdate(CREATOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.create(
                new CreateSettlementCommand("회식 정산", 10_000)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(AuthStatusCode.USER_NOT_FOUND));
        verify(saveSettlementPort, never()).save(any(Settlement.class));
        verify(saveSettlementParticipantPort, never()).save(any(SettlementParticipant.class));
    }

    @Test
    @DisplayName("생성자만 정산 기준을 바꿀 수 있도록 다른 사용자의 수정을 저장하지 않는다")
    void settlement_otherUserUpdate_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));

        assertThatThrownBy(() -> settlementService.update(
                SETTLEMENT_ID,
                new UpdateSettlementDraftCommand("변경된 제목", null)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("부분 수정 결과가 실제 영속 상태가 되도록 Domain 변경값을 저장 Port에 전달한다")
    void settlement_domainUpdate_persisted() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = settlementService.update(
                SETTLEMENT_ID,
                new UpdateSettlementDraftCommand(null, 20_000L)
        );

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(captor.capture());
        verify(allocationDraftInvalidator).invalidate(any(Settlement.class), any(LocalDateTime.class));
        assertThat(captor.getValue().title()).isEqualTo("회식 정산");
        assertThat(captor.getValue().totalAmount()).isEqualTo(20_000);
        assertThat(result.title()).isEqualTo("회식 정산");
        assertThat(result.totalAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("생성자가 요청 중인 정산을 수동 중단하고 저장한다")
    void settlement_creator_suspendRequested_suspendedAndSaved() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(requested()));
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = settlementService.suspend(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.SUSPENDED);
        verify(saveSettlementPort).update(argThat(settlement ->
                settlement.status() == SettlementStatus.SUSPENDED));
    }

    @Test
    @DisplayName("정산 재개 시 기존 품목 선택은 유지하고 제출 완료 표시만 초기화한다")
    void settlement_resume_preservesSelectionsAndClearsSubmissions() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(suspended()));
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = settlementService.resume(SETTLEMENT_ID);

        verify(settlementItemAllocationStorePort).deleteAllSubmissions(SETTLEMENT_ID);
        verify(saveSettlementPort).update(argThat(settlement ->
                settlement.status() == SettlementStatus.REQUESTED));
        assertThat(result.status()).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test
    @DisplayName("생성자가 아닌 사용자는 정산을 수동 중단하지 않는다")
    void settlement_otherUserManualSuspend_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(requested()));

        assertThatThrownBy(() -> settlementService.suspend(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("허용되지 않은 재개 요청은 제출 완료 표시를 삭제하지 않는다")
    void settlement_resumeWithoutClearingSubmissions_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(requested()));

        assertThatThrownBy(() -> settlementService.resume(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION));
        verify(settlementItemAllocationStorePort, never()).deleteAllSubmissions(SETTLEMENT_ID);
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("송금 단계 전인 생성자 정산만 삭제하고 root 삭제 Port를 호출한다")
    void settlement_beforeTransfer_creatorDelete_allowed() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(draft()));
        when(loadSettlementSharePort.existsBySettlementId(SETTLEMENT_ID)).thenReturn(false);

        settlementService.delete(SETTLEMENT_ID);

        verify(deleteSettlementPort).delete(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("송금 Share가 있는 정산은 삭제하지 않는다")
    void settlement_afterTransfer_delete_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(requested()));
        when(loadSettlementSharePort.existsBySettlementId(SETTLEMENT_ID)).thenReturn(true);

        assertThatThrownBy(() -> settlementService.delete(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE));
        verify(deleteSettlementPort, never()).delete(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("생성자가 아닌 사용자는 정산을 삭제하지 않는다")
    void rejectsDeletionByAnotherUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(draft()));

        assertThatThrownBy(() -> settlementService.delete(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verify(loadSettlementSharePort, never()).existsBySettlementId(SETTLEMENT_ID);
        verify(deleteSettlementPort, never()).delete(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("없는 정산은 삭제하지 않는다")
    void rejectsDeletingMissingSettlement() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.delete(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        verify(deleteSettlementPort, never()).delete(SETTLEMENT_ID);
    }

    private Settlement draft() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Settlement requested() {
        return draft().request(CREATED_AT.plusMinutes(1));
    }

    private Settlement suspended() {
        return requested().suspend(CREATED_AT.plusMinutes(2));
    }

    private User creator() {
        return new User(
                CREATOR_ID,
                "creator-identity-key",
                "최수아",
                "creator_public_id",
                null,
                null,
                "01012345678",
                "not-used",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
