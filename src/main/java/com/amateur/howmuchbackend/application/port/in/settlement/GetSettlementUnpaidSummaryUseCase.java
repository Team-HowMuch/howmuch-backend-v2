package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;

public interface GetSettlementUnpaidSummaryUseCase {
    SettlementUnpaidSummaryResult getSummary();
}
