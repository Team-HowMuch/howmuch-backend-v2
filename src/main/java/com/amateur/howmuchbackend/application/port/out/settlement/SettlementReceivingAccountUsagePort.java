package com.amateur.howmuchbackend.application.port.out.settlement;

public interface SettlementReceivingAccountUsagePort {
    boolean existsInProgressByReceivingAccountId(Long accountId);

    void detachCompletedOrDeletedByReceivingAccountId(Long accountId);
}
