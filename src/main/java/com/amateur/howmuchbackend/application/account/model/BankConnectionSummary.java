package com.amateur.howmuchbackend.application.account.model;

import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import java.time.LocalDateTime;

public record BankConnectionSummary(
        Long connectionId, String organization, ExternalConnectionStatus status, LocalDateTime connectedAt
) {
}
