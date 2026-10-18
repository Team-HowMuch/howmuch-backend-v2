package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;

public record FinanceOperation(
        String type,
        String organization,
        Long connectionId,
        Long accountId,
        String yearMonth,
        ConnectBankCommand registration,
        boolean replaceConnection,
        java.time.LocalDateTime collectedAt,
        java.time.LocalDate collectedThrough) {
    public FinanceOperation(String type, String organization, Long connectionId, Long accountId,
            String yearMonth, ConnectBankCommand registration, boolean replaceConnection) {
        this(type, organization, connectionId, accountId, yearMonth, registration, replaceConnection, null, null);
    }

    @Override
    public String toString() {
        return "FinanceOperation[type=" + type + "]";
    }
}
