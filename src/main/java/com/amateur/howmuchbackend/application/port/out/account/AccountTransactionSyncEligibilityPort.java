package com.amateur.howmuchbackend.application.port.out.account;

public interface AccountTransactionSyncEligibilityPort {
    boolean isEnabled(Long accountId);
}
