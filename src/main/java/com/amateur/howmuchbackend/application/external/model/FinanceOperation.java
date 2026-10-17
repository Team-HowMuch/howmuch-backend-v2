package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;

public record FinanceOperation(
        String type,
        String organization,
        Long connectionId,
        Long accountId,
        String yearMonth,
        ConnectBankCommand registration,
        boolean replaceConnection) {
    @Override
    public String toString() {
        return "FinanceOperation[type=" + type + "]";
    }
}
