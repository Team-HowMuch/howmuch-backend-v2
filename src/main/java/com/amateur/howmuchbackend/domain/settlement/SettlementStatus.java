package com.amateur.howmuchbackend.domain.settlement;

public enum SettlementStatus {
    DRAFT,
    REQUESTED,
    AWAITING_DEPOSIT,
    SUSPENDED,
    NETTING_IN_PROGRESS,
    COMPLETED
}
