package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.SendAutomaticSettlementRemindersUseCase;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementAutomaticReminderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.automatic-reminder", name = "enabled", havingValue = "true")
public class SettlementAutomaticReminderScheduler {
    private final SendAutomaticSettlementRemindersUseCase useCase;
    private final SettlementAutomaticReminderProperties properties;

    @Scheduled(fixedDelayString = "${howmuch.settlement.automatic-reminder.poll-interval:1m}",
            initialDelayString = "${howmuch.settlement.automatic-reminder.poll-interval:1m}")
    public void sendDue() { useCase.sendDue(properties.batchSize()); }
}
