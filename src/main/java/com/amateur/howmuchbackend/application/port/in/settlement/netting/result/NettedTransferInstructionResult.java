package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import java.util.UUID;

public record NettedTransferInstructionResult(
        UUID nettingId,
        UUID transferId,
        String organization,
        String accountNumber,
        String receiverName,
        long remainingAmount,
        String reference,
        String provider,
        String transferUrl) {
    @Override
    public String toString() {
        return "NettedTransferInstructionResult[redacted]";
    }
}
