package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import java.time.LocalDateTime;

public record BankConnectionSummaryResult(
        Long connectionId, String organization, String institutionName, String institutionIconUrl,
        ExternalConnectionStatus status, LocalDateTime connectedAt, long linkedAccountCount
) {
}
