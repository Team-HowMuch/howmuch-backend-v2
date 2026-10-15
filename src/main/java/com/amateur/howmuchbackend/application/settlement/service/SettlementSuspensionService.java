package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementSuspensionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementSuspensionService {
    private final CurrentActorPort currentActorPort;
    private final SettlementSuspensionReader suspensionReader;
    private final SettlementSuspensionWriter suspensionWriter;
    private final SettlementSuspensionPolicy suspensionPolicy;
    private final SettlementDepositService depositService;

    public SettlementResult suspend(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        SettlementSuspensionContext context = suspensionReader.prepare(
                settlementId,
                actor.userId()
        );
        boolean requiresRefresh = suspensionPolicy.requiresDepositRefresh(
                context.settlement(),
                context.shares(),
                context.participants(),
                context.submissions()
        );
        LocalDateTime refreshedThrough = null;
        if (requiresRefresh) {
            if (context.snapshot() == null) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                );
            }
            refreshedThrough = LocalDateTime.now(ZoneOffset.UTC);
            depositService.refreshForSuspension(
                    settlementId,
                    actor.userId(),
                    context.snapshot(),
                    context.settlement().status(),
                    refreshedThrough
            );
        }
        return suspensionWriter.suspend(context, actor.userId(), refreshedThrough);
    }
}
