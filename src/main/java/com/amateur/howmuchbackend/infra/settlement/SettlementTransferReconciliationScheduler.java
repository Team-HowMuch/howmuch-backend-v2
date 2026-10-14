package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessSettlementDepositReconciliationJobsUseCase;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementTransferReconciliationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix="howmuch.settlement.transfer-reconciliation", name="enabled", havingValue="true")
public class SettlementTransferReconciliationScheduler {
    private final ProcessSettlementDepositReconciliationJobsUseCase useCase;
    private final SettlementTransferReconciliationProperties properties;

    @Scheduled(scheduler="settlementTransferReconciliationTaskScheduler", fixedDelayString="${howmuch.settlement.transfer-reconciliation.poll-interval:5s}",
            initialDelayString="${howmuch.settlement.transfer-reconciliation.poll-interval:5s}")
    public void processDue() {
        try { useCase.processDue(properties.batchSize()); }
        catch (RuntimeException failure) {
            log.warn("Transfer reconciliation polling failed; durable jobs will be recovered on a later poll");
        }
    }
}
