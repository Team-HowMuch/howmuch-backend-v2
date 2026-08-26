package com.amateur.howmuchbackend.application.port.in.treasurer.result;

public record TreasurerSummaryResult(
        long sendAmount,
        int sendCount,
        long receiveAmount,
        int receiveCount,
        int inProgressCount,
        int completedCount,
        String message
) {
}
