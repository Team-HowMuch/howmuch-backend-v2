package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementSuspensionReader {
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort participantPort;
    private final LoadSettlementSharePort sharePort;
    private final SettlementItemAllocationStorePort allocationStorePort;
    private final LoadSettlementReceivingAccountSnapshotPort snapshotPort;

    @Transactional(readOnly = true)
    public SettlementSuspensionContext prepare(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        settlement.suspendManually(LocalDateTime.now(ZoneOffset.UTC));
        return new SettlementSuspensionContext(
                settlement,
                snapshotPort.findBySettlementId(settlementId).orElse(null),
                participantPort.findAllBySettlementId(settlementId),
                sharePort.findAllBySettlementId(settlementId),
                allocationStorePort.findAllSubmissions(settlementId)
        );
    }
}
