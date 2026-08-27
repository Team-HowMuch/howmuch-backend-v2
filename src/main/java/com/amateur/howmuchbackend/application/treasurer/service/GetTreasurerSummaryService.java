package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerSummaryResult;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetTreasurerSummaryService implements GetTreasurerSummaryUseCase {
    private final TreasurerSettlementQuery treasurerSettlementQuery;

    @Override
    @Transactional(readOnly = true)
    public TreasurerSummaryResult getSummary() {
        TreasurerSummary summary = TreasurerSummary.from(treasurerSettlementQuery.loadMine());
        return new TreasurerSummaryResult(
                summary.sendAmount(),
                summary.sendCount(),
                summary.receiveAmount(),
                summary.receiveCount(),
                summary.inProgressCount(),
                summary.completedCount(),
                summary.message()
        );
    }
}
