package com.amateur.howmuchbackend.application.port.in.treasurer.response;

public record TreasurerSummaryResponse(
        long sendAmount,
        int sendCount,
        long receiveAmount,
        int receiveCount,
        int inProgressCount,
        int completedCount,
        String message
) {
}
