package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingResult.ReminderBlockedReason;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import org.springframework.stereotype.Component;

@Component
public class NettedTransferReminderPolicy {
    public ReminderBlockedReason blockedReason(String status, int openReviews, long confirmedAmount) {
        if (!"ACTIVE".equals(status)) return ReminderBlockedReason.NETTING_NOT_ACTIVE;
        if (openReviews > 0) return ReminderBlockedReason.DEPOSIT_REVIEW_OPEN;
        if (confirmedAmount != 0) return ReminderBlockedReason.DEPOSIT_RECEIVED;
        return null;
    }

    public NettingStatusCode rejectionCode(ReminderBlockedReason reason) {
        return switch (reason) {
            case NETTING_NOT_ACTIVE -> NettingStatusCode.NETTING_REMINDER_NOT_ACTIVE;
            case DEPOSIT_REVIEW_OPEN -> NettingStatusCode.NETTING_REMINDER_DEPOSIT_REVIEW_OPEN;
            case DEPOSIT_RECEIVED -> NettingStatusCode.NETTING_REMINDER_DEPOSIT_RECEIVED;
        };
    }
}
