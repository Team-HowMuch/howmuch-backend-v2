package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;

import java.util.List;

public record FinanceAuthenticationCompletion(
        FinanceOperation operation,
        BankConnectionResult bankConnection,
        List<BankConnectionPort.ExternalAccountCandidate> candidates) {
    @Override
    public String toString() {
        return "FinanceAuthenticationCompletion[redacted]";
    }
}
