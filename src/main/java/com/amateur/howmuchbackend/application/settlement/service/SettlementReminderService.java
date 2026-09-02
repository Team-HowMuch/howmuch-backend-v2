package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RemindAllSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemindSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementBulkReminderResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementReminderService implements
        RemindSettlementParticipantUseCase,
        RemindAllSettlementParticipantsUseCase {
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementDepositSummaryPort loadSettlementDepositSummaryPort;
    private final LoadUserPort loadUserPort;
    private final SettlementReminderCooldownPort cooldownPort;
    private final SettlementReminderCooldownReader cooldownReader;
    private final SettlementPushSenderPort pushSenderPort;

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
        validateParticipant(settlement, participant);

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
        validateUnpaid(share, confirmedAmount);

        User target = loadUserPort.findById(participant.linkedUserId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        validatePushTarget(target);

        return sendReminder(settlementId, participantId, target.fcmToken());
    }

    @Override
    public SettlementBulkReminderResult remindAll(UUID settlementId) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        validateSettlement(settlement, actorId);

        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlementId);
        Map<UUID, SettlementShare> shareByParticipantId = loadSettlementSharePort
                .findAllBySettlementId(settlementId)
                .stream()
                .collect(Collectors.toMap(
                        SettlementShare::payerParticipantId,
                        Function.identity()
                ));
        Map<UUID, Long> confirmedByShareId = loadSettlementDepositSummaryPort
                .findConfirmedShareAmountsBySettlementId(settlementId)
                .stream()
                .collect(Collectors.toMap(
                        LoadSettlementDepositSummaryPort.ConfirmedShareAmount::shareId,
                        LoadSettlementDepositSummaryPort.ConfirmedShareAmount::amount,
                        Math::addExact
                ));
        List<Long> linkedUserIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> userById = linkedUserIds.isEmpty()
                ? Map.of()
                : loadUserPort.findAllByIds(linkedUserIds).stream()
                        .collect(Collectors.toMap(User::id, Function.identity()));

        int sentCount = 0;
        List<UUID> reminderCandidateIds = new ArrayList<>();
        for (SettlementParticipant participant : participants) {
            try {
                validateParticipant(settlement, participant);
                SettlementShare share = shareByParticipantId.get(participant.id());
                validateUnpaid(
                        share,
                        share == null ? 0 : confirmedByShareId.getOrDefault(share.id(), 0L)
                );
                User target = userById.get(participant.linkedUserId());
                if (target == null || !target.active()) {
                    throw ApplicationException.of(AuthStatusCode.USER_NOT_FOUND);
                }
                validatePushTarget(target);
                reminderCandidateIds.add(participant.id());
                sendReminder(settlementId, participant.id(), target.fcmToken());
                sentCount++;
            } catch (ApplicationException exception) {
                if (!isSkippableBulkFailure(exception)) {
                    throw exception;
                }
            }
        }
        LocalDateTime nextAvailableAt = cooldownReader.findLatestNextAvailableAt(
                settlementId,
                reminderCandidateIds
        ).orElse(null);
        return new SettlementBulkReminderResult(sentCount, nextAvailableAt);
    }

    private SettlementReminderResult sendReminder(
            UUID settlementId,
            UUID participantId,
            String firebaseInstallationId
    ) {

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
            SettlementPushSenderPort.SendResult sendResult = pushSenderPort.sendReminder(
                    firebaseInstallationId,
                    settlementId
            );
            if (sendResult == SettlementPushSenderPort.SendResult.INVALID_TARGET) {
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

    private void validateParticipant(
            Settlement settlement,
            SettlementParticipant participant
    ) {
        if (participant.linkedUserId() == null
                || participant.isLinkedTo(settlement.creatorId())) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED
            );
        }
    }

    private void validateUnpaid(SettlementShare share, long confirmedAmount) {
        if (share == null || confirmedAmount > 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID
            );
        }
    }

    private void validatePushTarget(User target) {
        if (target.fcmToken() == null || target.fcmToken().isBlank()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND
            );
        }
    }

    private boolean isSkippableBulkFailure(ApplicationException exception) {
        return exception.getStatusCode() == AuthStatusCode.USER_NOT_FOUND
                || exception.getStatusCode()
                == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED
                || exception.getStatusCode()
                == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID
                || exception.getStatusCode()
                == SettlementStatusCode.SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND
                || exception.getStatusCode()
                == SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE;
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
