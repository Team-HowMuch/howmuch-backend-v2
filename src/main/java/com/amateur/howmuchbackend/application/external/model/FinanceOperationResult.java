package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;

import java.util.List;

public record FinanceOperationResult(
        FinanceOperation operation,
        BankConnectionPort.ConnectionResult registration,
        List<BankConnectionPort.ExternalAccountCandidate> accounts,
        List<BankTransactionPort.BankTransaction> transactions) {
    @Override
    public String toString() {
        return "FinanceOperationResult[redacted]";
    }
}
