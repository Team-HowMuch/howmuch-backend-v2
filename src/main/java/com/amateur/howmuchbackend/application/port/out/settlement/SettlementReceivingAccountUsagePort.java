package com.amateur.howmuchbackend.application.port.out.settlement;

public interface SettlementReceivingAccountUsagePort {
    boolean existsInProgressByReceivingAccountId(Long accountId);

    void detachCompletedByReceivingAccountId(Long accountId);
}
