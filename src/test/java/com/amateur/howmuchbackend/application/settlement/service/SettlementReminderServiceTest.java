package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import java.time.Clock;
import java.time.ZoneOffset;
import static org.mockito.Mockito.lenient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementReminderServiceTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID SHARE_ID = UUID.randomUUID();
    private static final UUID UNLINKED_PARTICIPANT_ID = UUID.randomUUID();
    private static final Long CREATOR_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 9, 0);
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private SettlementItemAllocationStorePort itemAllocationStorePort;
    @Mock private LoadSettlementDepositSummaryPort loadSettlementDepositSummaryPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementReminderCooldownPort cooldownPort;
    @Mock private SettlementReminderCooldownReader cooldownReader;
    @Mock private NotificationRecorder recorder;
    @Mock private com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort automaticTargets;
    private SettlementReminderWriter writer;
    private SettlementReminderService service;

    @BeforeEach
    void setUp() {
        var policy = new SettlementReminderPolicy();
        writer = writerAt(NOW);
        service = new SettlementReminderService(currentActorPort, loadSettlementPort,
                loadSettlementParticipantPort, writer, policy, cooldownReader);
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    @AfterEach
    void clearTransaction() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test
    void reminder_missingFcmToken_storedAndCooldownCommitted() {
        givenTarget();
        when(cooldownPort.reserve(any(), any(), anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);
        var result = service.remind(SETTLEMENT_ID, PARTICIPANT_ID);
        assertThat(result.remindedAt()).isEqualTo(NOW);
        assertThat(result.nextAvailableAt()).isEqualTo(NOW.plusMinutes(30));
        var captured = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().recipientUserId()).isEqualTo(PAYER_ID);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(cooldownPort, never()).release(any(), any(), any());
    }

    @Test
    void itemSelectionReminder_requestedWithoutShare_stored() {
        givenRequestedTargetWithoutShare();
        when(loadUserPort.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        when(cooldownPort.reserve(any(), any(), anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);

        var result = service.remind(SETTLEMENT_ID, PARTICIPANT_ID);

        assertThat(result.nextAvailableAt()).isEqualTo(NOW.plusMinutes(30));
        var captured = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION);
        assertThat(captured.getValue().message()).isEqualTo("생성자님이 정산 품목 선택을 기다리고 있어요");
        verifyNoInteractions(loadSettlementDepositSummaryPort);
    }

    @Test
    void reminder_requestedParticipantAfterResume_preservesShare() {
        givenRequestedTargetWithoutShare();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(share()));
        when(loadUserPort.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        when(cooldownPort.reserve(any(), any(), anyString(), eq(Duration.ofMinutes(30)))).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);

        var result = service.remind(SETTLEMENT_ID, PARTICIPANT_ID);

        assertThat(result.nextAvailableAt()).isEqualTo(NOW.plusMinutes(30));
        var captured = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().message()).isEqualTo("생성자님이 정산 품목 선택을 기다리고 있어요");
    }

    @Test
    void reminder_submittedParticipantAfterResume_refused() {
        givenRequestedTargetWithoutShare();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(share()));
        when(itemAllocationStorePort.findSubmission(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(SettlementItemSubmission.submit(
                        SETTLEMENT_ID, PARTICIPANT_ID, NOW
                )));

        assertStatus(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID));

        verifyNoInteractions(cooldownPort, recorder);
    }

    @Test
    void reminder_commitFailure_releasesOwnedReservation() {
        givenTarget();
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);
        service.remind(SETTLEMENT_ID, PARTICIPANT_ID);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        var reservation = ArgumentCaptor.forClass(String.class);
        verify(cooldownPort).reserve(eq(SETTLEMENT_ID), eq(PARTICIPANT_ID), reservation.capture(), any());
        verify(cooldownPort).release(SETTLEMENT_ID, PARTICIPANT_ID, reservation.getValue());
    }

    @Test
    void reminder_storageFailure_releasesReservation() {
        givenTarget();
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID)).isInstanceOf(IllegalStateException.class);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(cooldownPort).release(eq(SETTLEMENT_ID), eq(PARTICIPANT_ID), anyString());
    }

    @Test
    void reminder_activeCooldown_refused() {
        givenTarget();
        assertStatus(SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID));
        verifyNoInteractions(recorder);
    }

    @Test
    void reminder_partialPayment_refused() {
        givenTarget();
        when(loadSettlementDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(SHARE_ID, 1)));
        assertStatus(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID));
        verifyNoInteractions(cooldownPort, recorder);
    }

    @Test
    void reminder_nonCreator_refused() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(PAYER_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        assertStatus(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID));
        verifyNoInteractions(loadSettlementParticipantPort, recorder);
    }

    @Test
    void bulkReminder_createdCountReturnedAndUnlinkedSkipped() {
        givenTarget();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(participant(), unlinkedParticipant()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(SETTLEMENT_ID, UNLINKED_PARTICIPANT_ID))
                .thenReturn(Optional.of(unlinkedParticipant()));
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);
        assertThat(service.remindAll(SETTLEMENT_ID).sentCount()).isEqualTo(1);
        verify(cooldownReader).findLatestNextAvailableAt(SETTLEMENT_ID, List.of(PARTICIPANT_ID));
    }

    @Test
    void bulkRemindsRequestedParticipantsWithoutShare() {
        givenRequestedTargetWithoutShare();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(requestedSettlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(participant()));
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);

        assertThat(service.remindAll(SETTLEMENT_ID).sentCount()).isEqualTo(1);

        verify(cooldownReader).findLatestNextAvailableAt(SETTLEMENT_ID, List.of(PARTICIPANT_ID));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-09-05T09:00:00,-1,false",
            "2026-09-05T09:00:00,0,true",
            "2026-09-05T09:00:00,1,true",
            "2026-09-05T18:00:00,0,false",
            "2026-09-05T21:00:00,0,true"
    })
    void automaticThresholdAndQuietHoursAreInclusive(
            LocalDateTime currentTime,
            long secondsAfterDeadline,
            boolean expected
    ) {
        writer = writerAt(currentTime);
        givenTarget();
        LocalDateTime reminderAnchor = currentTime.minusHours(12)
                .minusSeconds(secondsAfterDeadline);
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(new SettlementShare(SHARE_ID, SETTLEMENT_ID, PARTICIPANT_ID, 1000,
                        reminderAnchor)));
        if (expected) {
            when(automaticTargets.hasRequestNotification(SETTLEMENT_ID, PAYER_ID)).thenReturn(true);
            when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
            when(recorder.record(any())).thenReturn(true);
        }
        assertThat(writer.automatic(candidate())).isEqualTo(expected);
        if (expected) {
            var capture = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
            verify(recorder).record(capture.capture());
            assertThat(capture.getValue().deduplicationKey()).isEqualTo(
                    "AUTO_REMINDER:" + SHARE_ID + ":" + reminderAnchor.toEpochSecond(ZoneOffset.UTC)
            );
        } else verifyNoInteractions(recorder, cooldownPort);
    }

    @Test
    void automaticDoesNotBackfillLegacySettlements() {
        givenOldShare();
        assertThat(writer.automatic(candidate())).isFalse();
        verifyNoInteractions(recorder, cooldownPort);
    }

    @Test
    void automaticWaitsTwelveHoursAfterPreviousReminder() {
        givenOldShare();
        when(automaticTargets.hasRequestNotification(SETTLEMENT_ID, PAYER_ID)).thenReturn(true);
        when(automaticTargets.findLatestAutomaticReminderAt(SHARE_ID, PAYER_ID))
                .thenReturn(Optional.of(NOW.minusHours(12).plusSeconds(1)));
        assertThat(writer.automatic(candidate())).isFalse();
        verifyNoInteractions(recorder, cooldownPort);

        LocalDateTime reminderAnchor = NOW.minusHours(12);
        when(automaticTargets.findLatestAutomaticReminderAt(SHARE_ID, PAYER_ID))
                .thenReturn(Optional.of(reminderAnchor));
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);

        assertThat(writer.automatic(candidate())).isTrue();
        var capture = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
        verify(recorder).record(capture.capture());
        assertThat(capture.getValue().deduplicationKey()).isEqualTo(
                "AUTO_REMINDER:" + SHARE_ID + ":" + reminderAnchor.toEpochSecond(ZoneOffset.UTC)
        );
    }

    @Test
    void automaticRequestedReminderKeepsShareElapsedMessage() {
        givenTarget();
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(requestedSettlement()));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(new SettlementShare(
                        SHARE_ID, SETTLEMENT_ID, PARTICIPANT_ID, 1_000, NOW.minusHours(25))));
        when(loadUserPort.findById(CREATOR_ID)).thenReturn(Optional.of(creator()));
        when(automaticTargets.hasRequestNotification(SETTLEMENT_ID, PAYER_ID)).thenReturn(true);
        when(cooldownPort.reserve(any(), any(), anyString(), any())).thenReturn(true);
        when(recorder.record(any())).thenReturn(true);

        assertThat(writer.automatic(candidate())).isTrue();

        var captured = ArgumentCaptor.forClass(com.amateur.howmuchbackend.domain.notification.UserNotification.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().message()).isEqualTo("정산을 요청받은지 25시간이나 지났어요");
    }

    private void givenOldShare() {
        givenTarget();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(new SettlementShare(SHARE_ID, SETTLEMENT_ID, PARTICIPANT_ID, 1000, NOW.minusHours(25))));
    }
    private SettlementReminderWriter writerAt(LocalDateTime now) {
        return new SettlementReminderWriter(loadSettlementPort, loadSettlementParticipantPort,
                loadSettlementSharePort, itemAllocationStorePort, loadSettlementDepositSummaryPort,
                loadUserPort, cooldownPort, recorder, new SettlementReminderPolicy(),
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), automaticTargets);
    }
    private com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort.Target candidate() {
        return new com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort.Target(
                SETTLEMENT_ID, PARTICIPANT_ID, SHARE_ID, PAYER_ID);
    }

    private void givenTarget() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant()));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(share()));
        lenient().when(loadUserPort.findById(PAYER_ID)).thenReturn(Optional.of(user()));
    }
    private void givenRequestedTargetWithoutShare() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(requestedSettlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant()));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        lenient().when(itemAllocationStorePort.findSubmission(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        lenient().when(loadUserPort.findById(PAYER_ID)).thenReturn(Optional.of(user()));
    }
    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }
    private void assertStatus(SettlementStatusCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(code));
    }
    private Settlement settlement() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_000,
                SettlementStatus.AWAITING_DEPOSIT,
                NOW,
                NOW
        );
    }

    private Settlement requestedSettlement() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_000,
                SettlementAllocationType.ITEM_ALLOCATION,
                SettlementStatus.REQUESTED,
                NOW,
                NOW
        );
    }

    private SettlementParticipant participant() {
        return new SettlementParticipant(
                PARTICIPANT_ID,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                PAYER_ID,
                null,
                null,
                "참여자",
                1,
                NOW,
                NOW
        );
    }

    private SettlementParticipant unlinkedParticipant() {
        return new SettlementParticipant(
                UNLINKED_PARTICIPANT_ID,
                SETTLEMENT_ID,
                SettlementParticipantSource.CONTACT,
                null,
                "c".repeat(64),
                null,
                "비회원",
                2,
                NOW,
                NOW
        );
    }

    private SettlementShare share() {
        return new SettlementShare(SHARE_ID, SETTLEMENT_ID, PARTICIPANT_ID, 1_000, NOW);
    }

    private User user() {
        return new User(
                PAYER_ID,
                "identity-key",
                "참여자",
                "payer.id",
                null,
                null,
                "01012345678",
                "encoded-pin",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                NOW,
                null,
                NOW,
                NOW
        );
    }

    private User creator() {
        return new User(
                CREATOR_ID,
                "creator-identity-key",
                "생성자",
                "creator.id",
                null,
                null,
                "01087654321",
                "encoded-pin",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                NOW,
                null,
                NOW,
                NOW
        );
    }
}
