package com.amateur.howmuchbackend.application.port.in.settlement.result;

public record SettlementReceivingAccountResult(
        Long accountId,
        String organization,
        String institutionName,
        String institutionIconUrl,
        String name,
        String maskedAccountNumber,
        boolean isDefault,
        boolean selected
) {
}
