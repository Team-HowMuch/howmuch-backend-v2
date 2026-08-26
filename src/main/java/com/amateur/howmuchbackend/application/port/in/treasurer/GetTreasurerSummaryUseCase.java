package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerSummaryResponse;

public interface GetTreasurerSummaryUseCase {
    TreasurerSummaryResponse getSummary();
}
