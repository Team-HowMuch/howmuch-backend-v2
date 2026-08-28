package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerSummaryResult;

public interface GetTreasurerSummaryUseCase {
    TreasurerSummaryResult getSummary();
}
