package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceivingAccountResult;

public record SettlementReceivingAccountWebResponse(
        Long accountId,
        String organization,
        String institutionName,
        String institutionIconUrl,
        String name,
        String maskedAccountNumber,
        boolean isDefault,
        boolean selected
) {
    public static SettlementReceivingAccountWebResponse from(
            SettlementReceivingAccountResult result
    ) {
        return new SettlementReceivingAccountWebResponse(
                result.accountId(),
                result.organization(),
                result.institutionName(),
                result.institutionIconUrl(),
                result.name(),
                result.maskedAccountNumber(),
                result.isDefault(),
                result.selected()
        );
    }
}
