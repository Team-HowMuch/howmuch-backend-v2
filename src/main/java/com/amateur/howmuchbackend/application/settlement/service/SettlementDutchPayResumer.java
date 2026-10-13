package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementDutchPayResumer {
    private final LoadSettlementParticipantPort participantPort;
    private final LoadSettlementExpensePort expensePort;
    private final SettlementDutchPayAllocationStorePort allocationPort;
    private final SettlementRequestPolicy requestPolicy;
    private final SettlementRevisionGuard revisionGuard;
    private final DeleteSettlementSharePort deleteSharePort;
    private final SaveSettlementSharePort saveSharePort;

    // 호출자가 정산 행 잠금과 재개 트랜잭션을 소유한다.
    public Settlement resume(Settlement requested, LocalDateTime now) {
        revisionGuard.ensureUnpaid(requested.id());
        var shares = requestPolicy.prepareShares(requested,
                participantPort.findAllBySettlementId(requested.id()),
                expensePort.findAllBySettlementId(requested.id()), List.of(),
                allocationPort.findAllBySettlementId(requested.id()), now);
        deleteSharePort.deleteAllBySettlementId(requested.id());
        saveSharePort.saveAll(shares);
        return requested.awaitDeposit(now);
    }
}
