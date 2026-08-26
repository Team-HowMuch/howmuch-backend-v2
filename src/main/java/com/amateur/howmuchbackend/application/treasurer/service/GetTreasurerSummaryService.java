package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerSummaryResponse;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetTreasurerSummaryService implements GetTreasurerSummaryUseCase {
    private final TreasurerSettlementQuery treasurerSettlementQuery;

    @Override
    public TreasurerSummaryResponse getSummary() {
        TreasurerSummary summary = TreasurerSummary.from(treasurerSettlementQuery.loadMine());
        return new TreasurerSummaryResponse(
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
