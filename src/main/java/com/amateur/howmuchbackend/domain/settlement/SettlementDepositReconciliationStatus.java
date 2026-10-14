package com.amateur.howmuchbackend.domain.settlement;

public enum SettlementDepositReconciliationStatus {
    PENDING, PROCESSING, RETRY, SUCCEEDED, EXHAUSTED, CANCELLED;
    public boolean active() { return this == PENDING || this == PROCESSING || this == RETRY; }
}
