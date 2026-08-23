package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ImportSettlementReceiptExpenseWebRequest(
        @NotBlank(message = "OCR 영수증 참조는 필수입니다.")
        @Size(
                max = SettlementExpense.MAX_SOURCE_REFERENCE_LENGTH,
                message = "OCR 영수증 참조는 255자 이하여야 합니다."
        )
        @Schema(
                description = "외부 OCR 기능이 발급한 결과 참조",
                example = "receipt-demo"
        )
        String receiptReference
) {
    public ImportSettlementReceiptExpenseCommand toCommand() {
        return new ImportSettlementReceiptExpenseCommand(receiptReference.strip());
    }
}
