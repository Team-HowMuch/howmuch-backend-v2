package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;

import java.util.List;
import java.util.UUID;

public record SettlementDutchPayWebResponse(
        UUID settlementId,
        long totalAmount,
        long allocatedAmount,
        long differenceAmount,
        boolean balanced,
        List<SettlementDutchPayParticipantWebResponse> participants
) {
    public static SettlementDutchPayWebResponse from(SettlementDutchPayResult result) {
        return new SettlementDutchPayWebResponse(
                result.settlementId(),
                result.totalAmount(),
                result.allocatedAmount(),
                result.differenceAmount(),
                result.balanced(),
                result.participants().stream()
                        .map(SettlementDutchPayParticipantWebResponse::from)
                        .toList()
        );
    }
}
