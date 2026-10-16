package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.RemindNettedTransferUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettedTransferReminderStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettedTransferReminderService implements RemindNettedTransferUseCase {
    private final CurrentActorPort actor;
    private final GroupNettingAccessService access;
    private final NettedTransferReminderWriter writer;
    private final NettedTransferReminderStorePort reminders;
    private final SettlementReminderPolicy timePolicy;

    public void remind(long group, UUID id, UUID transfer) {
        long user = actor.getCurrentActor().userId();
        access.requireAccess(group, id, user);
        writer.remind(group, id, transfer, user, false, LocalDateTime.now(ZoneOffset.UTC));
    }

    public void remindAutomatically() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (timePolicy.isAutomaticQuietHours(now)) return;
        for (var candidate : reminders.automaticCandidates(now, 100))
            writer.remind(
                    candidate.groupId(),
                    candidate.nettingId(),
                    candidate.transferId(),
                    null,
                    true,
                    now);
    }
}
