package com.amateur.howmuchbackend.application.port.out.settlement;

public interface SettlementReceivingAccountUsagePort {
    boolean existsDraftByReceivingAccountId(Long accountId);

    void detachIssuedByReceivingAccountId(Long accountId);
}
