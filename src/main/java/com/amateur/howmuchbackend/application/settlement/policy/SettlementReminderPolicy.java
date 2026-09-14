package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class SettlementReminderPolicy {
    public static final Duration COOLDOWN = Duration.ofMinutes(30);

    public static final Duration AUTOMATIC_DELAY = Duration.ofHours(24);

    public boolean isAutomaticDue(Settlement settlement, SettlementShare share, LocalDateTime now) {
        return (settlement.status() == SettlementStatus.REQUESTED
                || settlement.status() == SettlementStatus.AWAITING_DEPOSIT
                || settlement.status() == SettlementStatus.SUSPENDED)
                && share != null && !share.createdAt().plus(AUTOMATIC_DELAY).isAfter(now);
    }

    public void validateManual(Settlement settlement, Long actorId) {
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (settlement.status() != SettlementStatus.REQUESTED
                && settlement.status() != SettlementStatus.AWAITING_DEPOSIT) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_NOT_AVAILABLE);
        }
    }

    public void validateManualTarget(Settlement settlement, SettlementParticipant participant,
            SettlementShare share, long confirmedAmount, boolean itemSelectionSubmitted) {
        validateLinkedTarget(settlement, participant);
        if (settlement.status() == SettlementStatus.REQUESTED) {
            if (itemSelectionSubmitted || share != null) {
                throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID);
            }
            return;
        }
        validateUnpaidTarget(share, confirmedAmount);
    }

    public void validateAutomaticTarget(Settlement settlement, SettlementParticipant participant,
            SettlementShare share, long confirmedAmount) {
        validateLinkedTarget(settlement, participant);
        validateUnpaidTarget(share, confirmedAmount);
    }

    private void validateLinkedTarget(Settlement settlement, SettlementParticipant participant) {
        if (participant.linkedUserId() == null || participant.isLinkedTo(settlement.creatorId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED);
        }
    }

    private void validateUnpaidTarget(SettlementShare share, long confirmedAmount) {
        if (share == null || confirmedAmount != 0) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID);
        }
    }
}
