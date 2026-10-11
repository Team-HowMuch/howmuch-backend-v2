package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionSummaryResult;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import java.time.LocalDateTime;

public record BankConnectionSummaryWebResponse(
        Long connectionId, String organization, String institutionName, String institutionIconUrl,
        ExternalConnectionStatus status, LocalDateTime connectedAt, long linkedAccountCount
) {
    public static BankConnectionSummaryWebResponse from(BankConnectionSummaryResult result) {
        return new BankConnectionSummaryWebResponse(result.connectionId(), result.organization(),
                result.institutionName(), result.institutionIconUrl(), result.status(),
                result.connectedAt(), result.linkedAccountCount());
    }
}
