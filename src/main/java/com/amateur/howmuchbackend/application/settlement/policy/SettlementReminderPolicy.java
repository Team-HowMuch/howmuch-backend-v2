package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Component
public class SettlementReminderPolicy {
    public static final Duration COOLDOWN = Duration.ofMinutes(30);

    public static final Duration AUTOMATIC_INTERVAL = Duration.ofHours(12);
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime QUIET_HOURS_START = LocalTime.of(3, 0);
    private static final LocalTime QUIET_HOURS_END = LocalTime.of(6, 0);

    public boolean supportsManualReminder(SettlementStatus status) {
        return status == SettlementStatus.REQUESTED || status == SettlementStatus.AWAITING_DEPOSIT;
    }

    public boolean isManualReminderCandidate(
            SettlementStatus status, boolean linked, boolean submitted, boolean unpaid
    ) {
        return linked && switch (status) {
            case REQUESTED -> !submitted;
            case AWAITING_DEPOSIT -> unpaid;
            default -> false;
        };
    }

    public boolean isAutomaticDue(
            Settlement settlement,
            SettlementShare share,
            LocalDateTime reminderAnchor,
            LocalDateTime now
    ) {
        return (settlement.status() == SettlementStatus.REQUESTED
                || settlement.status() == SettlementStatus.AWAITING_DEPOSIT
                || settlement.status() == SettlementStatus.SUSPENDED)
                && share != null
                && !reminderAnchor.plus(AUTOMATIC_INTERVAL).isAfter(now)
                && !isAutomaticQuietHours(now);
    }

    public boolean isAutomaticQuietHours(LocalDateTime now) {
        LocalTime koreaTime = now.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(KOREA_ZONE)
                .toLocalTime();
        return !koreaTime.isBefore(QUIET_HOURS_START)
                && koreaTime.isBefore(QUIET_HOURS_END);
    }

    public void validateManual(Settlement settlement, Long actorId) {
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (!supportsManualReminder(settlement.status())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_NOT_AVAILABLE);
        }
    }

    public void validateManualTarget(Settlement settlement, SettlementParticipant participant,
            SettlementShare share, long confirmedAmount, boolean itemSelectionSubmitted) {
        validateLinkedTarget(settlement, participant);
        if (settlement.status() == SettlementStatus.REQUESTED) {
            if (itemSelectionSubmitted) {
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
