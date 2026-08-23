package com.amateur.howmuchbackend.application.port.in.account.result;

import java.time.LocalDateTime;

public record AccountSummaryResult(
        Long id,
        String organization,
        String institutionName,
        String institutionIconUrl,
        String name,
        String maskedAccountNumber,
        boolean isDefault,
        int displayOrder,
        LocalDateTime lastUsedAt
) {
}
