package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;

import java.util.List;
import java.util.UUID;

public record SettlementTransferInstructionWebResponse(
        UUID settlementId,
        UUID participantId,
        UUID shareId,
        long amount,
        String recipientBankCode,
        String recipientAccountNumber,
        List<TransferLinkWebResponse> transferLinks
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
                result.recipientAccountNumber(),
                result.transferLinks().stream()
                        .map(link -> new TransferLinkWebResponse(link.provider(), link.url()))
                        .toList()
        );
    }

    public record TransferLinkWebResponse(String provider, String url) {
    }
}
