package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettedTransferInstructionResult;

import java.util.UUID;

public record NettedTransferInstructionWebResponse(
        UUID nettingId,
        UUID transferId,
        String organization,
        String accountNumber,
        String receiverName,
        long remainingAmount,
        String reference,
        String provider,
        String transferUrl) {
    public static NettedTransferInstructionWebResponse from(NettedTransferInstructionResult r) {
        return new NettedTransferInstructionWebResponse(
                r.nettingId(),
                r.transferId(),
                r.organization(),
                r.accountNumber(),
                r.receiverName(),
                r.remainingAmount(),
                r.reference(),
                r.provider(),
                r.transferUrl());
    }

    @Override
    public String toString() {
        return "NettedTransferInstructionWebResponse[redacted]";
    }
}
