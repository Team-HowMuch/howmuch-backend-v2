package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;
import java.util.UUID;

public record SettlementTransferInstructionResult(
        UUID settlementId,
        UUID participantId,
        UUID shareId,
        long amount,
        String recipientBankCode,
        String recipientAccountNumber,
        List<TransferLink> transferLinks
) {
    public record TransferLink(String provider, String url) {
    }
}
