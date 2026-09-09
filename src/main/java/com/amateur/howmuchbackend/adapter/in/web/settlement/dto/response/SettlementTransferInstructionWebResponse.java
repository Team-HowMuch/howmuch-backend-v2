package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record SettlementTransferInstructionWebResponse(
        UUID settlementId,
        UUID participantId,
        UUID shareId,
        @Schema(description = "확인된 입금액을 차감한 미납 잔액. 완납·초과 납부는 0", minimum = "0")
        long amount,
        String recipientName,
        String recipientBankCode,
        String recipientBankName,
        String recipientAccountNumber,
        @Schema(description = "미납 잔액의 송금 링크. 완납·초과 납부는 빈 배열")
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
                result.recipientName(),
                result.recipientBankCode(),
                result.recipientBankName(),
                result.recipientAccountNumber(),
                result.transferLinks().stream()
                        .map(link -> new TransferLinkWebResponse(link.provider(), link.url()))
                        .toList()
        );
    }

    public record TransferLinkWebResponse(String provider, String url) {
    }
}
