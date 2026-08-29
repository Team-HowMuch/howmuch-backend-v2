package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RemindSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementReminderService implements RemindSettlementParticipantUseCase {
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementDepositSummaryPort loadSettlementDepositSummaryPort;
    private final LoadUserPort loadUserPort;
    private final SettlementReminderCooldownPort cooldownPort;
    private final SettlementReminderPushSenderPort pushSenderPort;

    @Override
    public SettlementReminderResult remind(UUID settlementId, UUID participantId) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        validateSettlement(settlement, actorId);

        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlementId, participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        if (participant.linkedUserId() == null
                || participant.isLinkedTo(settlement.creatorId())) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED
            );
        }

        SettlementShare share = loadSettlementSharePort
                .findBySettlementIdAndPayerParticipantId(settlementId, participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID
                ));
        long confirmedAmount = loadSettlementDepositSummaryPort
                .findConfirmedShareAmountsBySettlementId(settlementId)
                .stream()
                .filter(amount -> amount.shareId().equals(share.id()))
                .mapToLong(LoadSettlementDepositSummaryPort.ConfirmedShareAmount::amount)
                .sum();
        if (confirmedAmount > 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID
            );
        }

        User target = loadUserPort.findById(participant.linkedUserId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (target.fcmToken() == null || target.fcmToken().isBlank()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND
            );
        }

        LocalDateTime remindedAt = LocalDateTime.now(ZoneOffset.UTC);
        String reservationId = UUID.randomUUID().toString();
        if (!cooldownPort.reserve(
                settlementId,
                participantId,
                reservationId,
                COOLDOWN
        )) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE
            );
        }

        try {
            SettlementReminderPushSenderPort.SendResult sendResult = pushSenderPort.send(
                    target.fcmToken(),
                    settlementId
            );
            if (sendResult == SettlementReminderPushSenderPort.SendResult.INVALID_TARGET) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND
                );
            }
        } catch (RuntimeException exception) {
            releaseReservation(settlementId, participantId, reservationId, exception);
            throw exception;
        }

        return new SettlementReminderResult(remindedAt, remindedAt.plus(COOLDOWN));
    }

    private void validateSettlement(Settlement settlement, Long actorId) {
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (settlement.status() != SettlementStatus.AWAITING_DEPOSIT) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_NOT_AVAILABLE
            );
        }
    }

    private void releaseReservation(
            UUID settlementId,
            UUID participantId,
            String reservationId,
            RuntimeException originalException
    ) {
        try {
            cooldownPort.release(settlementId, participantId, reservationId);
        } catch (RuntimeException releaseException) {
            originalException.addSuppressed(releaseException);
        }
    }
}
