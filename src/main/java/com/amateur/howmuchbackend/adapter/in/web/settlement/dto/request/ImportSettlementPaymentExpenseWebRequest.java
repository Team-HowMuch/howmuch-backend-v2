package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementPaymentExpenseCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ImportSettlementPaymentExpenseWebRequest(
        @NotNull(message = "계좌 ID는 필수입니다.")
        @Positive(message = "계좌 ID는 양수여야 합니다.")
        @Schema(description = "결제내역을 소유한 연결 계좌 ID", example = "1")
        Long accountId,

        @NotNull(message = "결제내역 ID는 필수입니다.")
        @Positive(message = "결제내역 ID는 양수여야 합니다.")
        @Schema(description = "정산 지출로 가져올 결제내역 ID", example = "10")
        Long transactionId
) {
    public ImportSettlementPaymentExpenseCommand toCommand() {
        return new ImportSettlementPaymentExpenseCommand(accountId, transactionId);
    }
}
