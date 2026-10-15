package com.amateur.howmuchbackend.application.port.in.settlement;


public interface ProcessSettlementDepositReconciliationJobsUseCase {
    int processDue(int limit);
}
