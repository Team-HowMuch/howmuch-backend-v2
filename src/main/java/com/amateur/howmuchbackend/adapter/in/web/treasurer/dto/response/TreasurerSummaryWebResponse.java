package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerSummaryResult;

public record TreasurerSummaryWebResponse(
        long sendAmount,
        int sendCount,
        long receiveAmount,
        int receiveCount,
        int inProgressCount,
        int completedCount,
        String message
) {
    public static TreasurerSummaryWebResponse from(TreasurerSummaryResult result) {
        return new TreasurerSummaryWebResponse(
                result.sendAmount(),
                result.sendCount(),
                result.receiveAmount(),
                result.receiveCount(),
                result.inProgressCount(),
                result.completedCount(),
                result.message()
        );
    }
}
