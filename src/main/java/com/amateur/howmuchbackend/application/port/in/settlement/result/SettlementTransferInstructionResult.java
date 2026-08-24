package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.UUID;

public record SettlementTransferInstructionResult(
        UUID settlementId,
        UUID participantId,
        UUID shareId,
        long amount,
        String recipientBankCode,
        String recipientAccountNumber
) {
}
