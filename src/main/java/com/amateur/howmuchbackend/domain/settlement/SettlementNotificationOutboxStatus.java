package com.amateur.howmuchbackend.domain.settlement;

public enum SettlementNotificationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY,
    SUCCEEDED,
    FAILED
}
