package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;

import java.util.UUID;

public record SettlementTransferInstructionWebResponse(
        UUID settlementId,
        UUID participantId,
        UUID shareId,
        long amount,
        String recipientBankCode,
        String recipientAccountNumber
) {
    public static SettlementTransferInstructionWebResponse from(
            SettlementTransferInstructionResult result
    ) {
        return new SettlementTransferInstructionWebResponse(
                result.settlementId(),
                result.participantId(),
                result.shareId(),
                result.amount(),
                result.recipientBankCode(),
                result.recipientAccountNumber()
        );
    }
}
