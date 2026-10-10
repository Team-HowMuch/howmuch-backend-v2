package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUnpaidSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementUnpaidSummaryPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementUnpaidSummaryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementUnpaidSummaryService implements GetSettlementUnpaidSummaryUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementUnpaidSummaryPort loadSettlementUnpaidSummaryPort;
    private final SettlementUnpaidSummaryPolicy summaryPolicy;

    @Override
    @Transactional(readOnly = true)
    public SettlementUnpaidSummaryResult getSummary() {
        Long creatorId = currentActorPort.getCurrentActor().userId();
        return summaryPolicy.summarize(loadSettlementUnpaidSummaryPort.findAllByCreatorId(creatorId));
    }
}
