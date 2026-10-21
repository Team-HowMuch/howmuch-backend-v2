package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettedTransferReminderPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettedTransferReminderWriter {
    private final LoadGroupPort groups;
    private final LoadGroupNettingPort nettings;
    private final SaveGroupNettingPort locking;
    private final NettedTransferReminderStorePort reminders;
    private final NettingNotificationService notifications;
    private final LoadGroupNettingDebtPort inputs;
    private final NettingTransferAvailabilityPort availability;
    private final NettedTransferReminderPolicy reminderPolicy;

    @Transactional
    public void remind(
            long group,
            UUID id,
            UUID transferId,
            Long actor,
            boolean automatic,
            LocalDateTime now) {
        var initial = nettings.find(group, id).orElseThrow();
        inputs.lockPaymentInputs(initial);
        groups.findByIdForUpdate(group).orElseThrow();
        if (availability.isGroupReplacementPending(group)) {
            if (automatic) return;
            throw ApplicationException.of(
                    com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode
                            .NETTING_PROCESSING);
        }
        locking.lockNetting(id);
        var n = nettings.find(group, id).orElseThrow();
        var t =
                n.transfers().stream()
                        .filter(v -> v.id().equals(transferId))
                        .findFirst()
                        .orElseThrow();
        if (actor != null && t.receiverId() != actor)
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        var blockedReason = reminderPolicy.blockedReason(n.status(), n.openReviews(), t.confirmedAmount());
        if (blockedReason != null) {
            if (automatic) return;
            throw ApplicationException.of(reminderPolicy.rejectionCode(blockedReason));
        }
        var interval =
                automatic
                        ? com.amateur.howmuchbackend.application.settlement.policy
                                .SettlementReminderPolicy.AUTOMATIC_INTERVAL
                        : com.amateur.howmuchbackend.application.settlement.policy
                                .SettlementReminderPolicy.COOLDOWN;
        if (automatic && t.activatedAt().plus(interval).isAfter(now)) return;
        var sequence = reminders.reserve(t.id(), now, interval);
        if (sequence.isEmpty()) {
            if (automatic) return;
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE);
        }
        notifications.reminder(
                t,
                (automatic ? "AUTO_NETTED_REMINDER:" : "NETTED_REMINDER:")
                        + t.id()
                        + ":"
                        + sequence.getAsLong(),
                now);
    }
}
