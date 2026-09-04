package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.notification.service.NotificationMessages;
import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementReminderWriter {
    private final LoadSettlementPort settlements;
    private final LoadSettlementParticipantPort participants;
    private final LoadSettlementSharePort shares;
    private final LoadSettlementDepositSummaryPort deposits;
    private final LoadUserPort users;
    private final SettlementReminderCooldownPort cooldown;
    private final NotificationRecorder recorder;
    private final SettlementReminderPolicy policy;
    private final Clock clock;
    private final SettlementAutomaticReminderTargetPort automaticTargets;

    @Transactional
    public SettlementReminderResult manual(UUID settlementId, UUID participantId, Long actorId) {
        Settlement settlement = settlements.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        policy.validateManual(settlement, actorId);
        Target target = loadTarget(settlement, participantId);
        LocalDateTime now = LocalDateTime.now(clock);
        store(settlement, target, "MANUAL_REMINDER:" + UUID.randomUUID(),
                "정산을 요청받았어요. 지금 정산을 완료해 주세요.", now);
        return new SettlementReminderResult(now, now.plus(SettlementReminderPolicy.COOLDOWN));
    }

    @Transactional
    public boolean automatic(SettlementAutomaticReminderTargetPort.Target candidate) {
        var settlement = settlements.findByIdForUpdate(candidate.settlementId()).orElse(null);
        if (settlement == null) return false;
        Target target = loadTarget(settlement, candidate.participantId());
        LocalDateTime now = LocalDateTime.now(clock);
        if (!target.share().id().equals(candidate.shareId())
                || !target.recipient().id().equals(candidate.recipientUserId())
                || !policy.isAutomaticDue(settlement, target.share(), now)
                || !automaticTargets.hasRequestNotification(settlement.id(), target.recipient().id())
                || automaticTargets.hasAutomaticReminder(target.share().id(), target.recipient().id())) {
            return false;
        }
        long hours = java.time.Duration.between(target.share().createdAt(), now).toHours();
        return store(settlement, target, "AUTO_REMINDER:" + target.share().id(),
                "정산 금액이 확정된 지 " + hours + "시간이 지났어요. 지금 정산을 완료해 주세요.", now);
    }

    private Target loadTarget(Settlement settlement, UUID participantId) {
        SettlementParticipant participant = participants.findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND));
        SettlementShare share = shares.findBySettlementIdAndPayerParticipantId(settlement.id(), participantId)
                .orElse(null);
        long confirmed = share == null ? 0 : deposits.findConfirmedShareAmountsBySettlementId(settlement.id())
                .stream().filter(amount -> amount.shareId().equals(share.id()))
                .mapToLong(LoadSettlementDepositSummaryPort.ConfirmedShareAmount::amount).sum();
        policy.validateTarget(settlement, participant, share, confirmed);
        User recipient = users.findById(participant.linkedUserId()).filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        return new Target(participant, share, recipient);
    }

    private boolean store(Settlement settlement, Target target, String key, String message, LocalDateTime now) {
        String requester = users.findById(settlement.creatorId()).map(User::name).orElse("정산 요청자");
        String reservation = UUID.randomUUID().toString();
        if (!cooldown.reserve(settlement.id(), target.participant().id(), reservation, SettlementReminderPolicy.COOLDOWN)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE);
        }
        // DB commit failures also release only the Redis reservation owned by this transaction.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    release(settlement.id(), target.participant().id(), reservation);
                }
            }
        });
        boolean created = recorder.record(NotificationMessages.reminder(settlement.id(), target.recipient().id(),
                settlement.allocationType(), requester, key, message, now));
        if (!created) {
            release(settlement.id(), target.participant().id(), reservation);
        }
        return created;
    }

    private void release(UUID settlementId, UUID participantId, String reservation) {
        try {
            cooldown.release(settlementId, participantId, reservation);
        } catch (RuntimeException exception) {
            log.warn("Reminder reservation release failed. settlementId={}, participantId={}, failureType={}",
                    settlementId, participantId, exception.getClass().getSimpleName());
        }
    }

    private record Target(SettlementParticipant participant, SettlementShare share, User recipient) {}
}
