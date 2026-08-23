package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;

import java.time.LocalDateTime;

public record AccountSummaryWebResponse(
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
    public static AccountSummaryWebResponse from(AccountSummaryResult result) {
        return new AccountSummaryWebResponse(
                result.id(), result.organization(), result.institutionName(), result.institutionIconUrl(),
                result.name(), result.maskedAccountNumber(), result.isDefault(), result.displayOrder(),
                result.lastUsedAt()
        );
    }
}
