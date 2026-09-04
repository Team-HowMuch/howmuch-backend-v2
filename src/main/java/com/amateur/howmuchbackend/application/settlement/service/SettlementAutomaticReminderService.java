package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.SendAutomaticSettlementRemindersUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementAutomaticReminderService implements SendAutomaticSettlementRemindersUseCase {
    private final SettlementAutomaticReminderTargetPort targets;
    private final SettlementReminderWriter writer;
    private final Clock clock;

    @Override
    public int sendDue(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        LocalDateTime now = LocalDateTime.now(clock);
        int created = 0;
        for (var target : targets.findDue(now.minus(SettlementReminderPolicy.AUTOMATIC_DELAY),
                now.minus(SettlementReminderPolicy.COOLDOWN), batchSize)) {
            try {
                if (writer.automatic(target)) created++;
            } catch (RuntimeException exception) {
                if (!isNoLongerEligible(exception)) {
                    log.warn("Automatic reminder failed. shareId={}, failureType={}",
                            target.shareId(), exception.getClass().getSimpleName());
                }
            }
        }
        return created;
    }

    private boolean isNoLongerEligible(RuntimeException exception) {
        if (!(exception instanceof ApplicationException application)) return false;
        var code = application.getStatusCode();
        return code == SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE
                || code == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID
                || code == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED
                || code == SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                || code == AuthStatusCode.USER_NOT_FOUND;
    }
}
