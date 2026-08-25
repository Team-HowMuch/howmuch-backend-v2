package com.amateur.howmuchbackend.application.port.in.settlement.command;

public enum SettlementListStatusFilter {
    ALL,
    IN_PROGRESS,
    DRAFT,
    REQUESTED,
    AWAITING_DEPOSIT,
    SUSPENDED,
    COMPLETED
}
