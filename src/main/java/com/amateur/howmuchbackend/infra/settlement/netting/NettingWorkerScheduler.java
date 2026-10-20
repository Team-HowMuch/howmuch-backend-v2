package com.amateur.howmuchbackend.infra.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.DispatchNettingExecutionsUseCase;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingWorkerScheduler {
    private final DispatchNettingExecutionsUseCase dispatcher;
    private final com.amateur.howmuchbackend.application.port.in.settlement.netting
                    .RemindNettedTransferUseCase
            reminders;

    @Scheduled(
            scheduler = "nettingJobTaskScheduler",
            fixedDelayString = "${howmuch.settlement.netting.dispatch-delay-ms:5000}")
    public void dispatch() {
        dispatcher.dispatch();
    }

    @Scheduled(scheduler = "nettingHeartbeatScheduler", fixedDelay = 20000)
    public void heartbeat() {
        dispatcher.heartbeat();
    }

    @Scheduled(scheduler = "nettingReminderScheduler", fixedDelay = 60000)
    public void remind() {
        reminders.remindAutomatically();
    }
}
