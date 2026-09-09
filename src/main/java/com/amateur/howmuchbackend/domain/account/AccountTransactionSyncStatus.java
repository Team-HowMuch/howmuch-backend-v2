package com.amateur.howmuchbackend.domain.account;

public enum AccountTransactionSyncStatus {
    QUEUED,
    COLLECTING,
    APPLYING,
    SUCCEEDED,
    REAUTH_REQUIRED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
