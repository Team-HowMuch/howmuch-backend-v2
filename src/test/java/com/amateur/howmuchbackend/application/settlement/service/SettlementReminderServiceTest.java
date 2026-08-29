package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
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
@DisplayName("미납 참여자 앱 푸시 독촉")
class SettlementReminderServiceTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final UUID SHARE_ID = UUID.fromString("063338bb-bc17-471a-b917-723f37788857");
    private static final UUID UNLINKED_PARTICIPANT_ID = UUID.fromString("ee3c8674-7d99-4fe3-bf21-154ed54b532a");
    private static final Long CREATOR_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 29, 9, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private LoadSettlementDepositSummaryPort loadSettlementDepositSummaryPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementReminderCooldownPort cooldownPort;
    @Mock private SettlementReminderPushSenderPort pushSenderPort;

    private SettlementReminderService service;

    @BeforeEach
    void setUp() {
        service = new SettlementReminderService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementSharePort,
                loadSettlementDepositSummaryPort,
                loadUserPort,
                cooldownPort,
                pushSenderPort
        );
    }

    @Test
    @DisplayName("생성자가 입금 전 회원에게 푸시를 보내고 30분 재요청 시각을 반환한다")
    void sendsReminderToUnpaidLinkedParticipant() {
        givenUnpaidTarget();
        when(cooldownPort.reserve(
                eq(SETTLEMENT_ID),
                eq(PARTICIPANT_ID),
                anyString(),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(pushSenderPort.send("firebase-installation-id", SETTLEMENT_ID))
                .thenReturn(SettlementReminderPushSenderPort.SendResult.SENT);

        var result = service.remind(SETTLEMENT_ID, PARTICIPANT_ID);

        assertThat(Duration.between(result.remindedAt(), result.nextAvailableAt()))
                .isEqualTo(Duration.ofMinutes(30));
        verify(pushSenderPort).send("firebase-installation-id", SETTLEMENT_ID);
        verify(cooldownPort, never()).release(any(), any(), anyString());
    }

    @Test
    @DisplayName("전체 독촉은 발송 가능한 미납 회원만 보내고 실제 발송 수를 반환한다")
    void sendsBulkRemindersToEligibleParticipants() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(participant(), unlinkedParticipant()));
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(share()));
        when(loadSettlementDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(loadUserPort.findAllByIds(List.of(PAYER_ID))).thenReturn(List.of(user()));
        when(cooldownPort.reserve(
                eq(SETTLEMENT_ID),
                eq(PARTICIPANT_ID),
                anyString(),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(pushSenderPort.send("firebase-installation-id", SETTLEMENT_ID))
                .thenReturn(SettlementReminderPushSenderPort.SendResult.SENT);

        var result = service.remindAll(SETTLEMENT_ID);

        assertThat(result.sentCount()).isEqualTo(1);
        verify(pushSenderPort).send("firebase-installation-id", SETTLEMENT_ID);
    }

    @Test
    @DisplayName("일부라도 입금된 참여자는 독촉하지 않는다")
    void rejectsPartiallyPaidParticipant() {
        givenTargetBeforeDepositLookup();
        when(loadSettlementDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(SHARE_ID, 100)
                ));

        assertStatus(
                SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID)
        );

        verifyNoInteractions(cooldownPort, pushSenderPort);
    }

    @Test
    @DisplayName("같은 참여자의 30분 예약이 있으면 푸시를 중복 발송하지 않는다")
    void rejectsReminderDuringCooldown() {
        givenUnpaidTarget();
        when(cooldownPort.reserve(
                eq(SETTLEMENT_ID),
                eq(PARTICIPANT_ID),
                anyString(),
                eq(Duration.ofMinutes(30))
        )).thenReturn(false);

        assertStatus(
                SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID)
        );

        verifyNoInteractions(pushSenderPort);
    }

    @Test
    @DisplayName("푸시 발송이 실패하면 자신의 Redis 예약만 해제한다")
    void releasesReservationWhenDeliveryFails() {
        givenUnpaidTarget();
        when(cooldownPort.reserve(
                eq(SETTLEMENT_ID),
                eq(PARTICIPANT_ID),
                anyString(),
                eq(Duration.ofMinutes(30))
        )).thenReturn(true);
        when(pushSenderPort.send("firebase-installation-id", SETTLEMENT_ID))
                .thenThrow(ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED
                ));

        assertStatus(
                SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID)
        );

        ArgumentCaptor<String> reservationCaptor = ArgumentCaptor.forClass(String.class);
        verify(cooldownPort).reserve(
                eq(SETTLEMENT_ID),
                eq(PARTICIPANT_ID),
                reservationCaptor.capture(),
                eq(Duration.ofMinutes(30))
        );
        verify(cooldownPort).release(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                reservationCaptor.getValue()
        );
    }

    @Test
    @DisplayName("정산 생성자가 아닌 사용자는 대상 상태를 조회하기 전에 거부한다")
    void rejectsNonCreator() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(PAYER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));

        assertStatus(
                SettlementStatusCode.SETTLEMENT_ACCESS_DENIED,
                () -> service.remind(SETTLEMENT_ID, PARTICIPANT_ID)
        );

        verifyNoInteractions(loadSettlementParticipantPort, cooldownPort, pushSenderPort);
    }

    private void givenUnpaidTarget() {
        givenTargetBeforeDepositLookup();
        when(loadSettlementDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(loadUserPort.findById(PAYER_ID)).thenReturn(Optional.of(user()));
    }

    private void givenTargetBeforeDepositLookup() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(Optional.of(participant()));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                PARTICIPANT_ID
        )).thenReturn(Optional.of(share()));
    }

    private void assertStatus(SettlementStatusCode statusCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(statusCode));
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
                "firebase-installation-id",
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
