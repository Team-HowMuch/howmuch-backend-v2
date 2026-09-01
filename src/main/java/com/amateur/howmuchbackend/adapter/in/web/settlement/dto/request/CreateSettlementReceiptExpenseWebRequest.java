package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record CreateSettlementReceiptExpenseWebRequest(
        @NotBlank(message = "영수증 참조는 필수입니다.")
        @Size(
                max = SettlementExpense.MAX_SOURCE_REFERENCE_LENGTH,
                message = "영수증 참조는 255자 이하여야 합니다."
        )
        @Schema(
                description = "앱이 영수증 스캔마다 생성한 재시도 중복 방지 참조",
                example = "9ad0c7cb-cf18-44bb-9c4b-87673f3201e8"
        )
        String receiptReference,
        @NotBlank(message = "상호명은 필수입니다.")
        @Size(
                max = SettlementExpense.MAX_DESCRIPTION_LENGTH,
                message = "상호명은 500자 이하여야 합니다."
        )
        @Schema(description = "영수증 상호명", example = "롯데리아")
        String storeName,
        @Schema(
                description = "영수증 결제 시각. 인식할 수 없으면 null",
                example = "2026-08-31T17:40:00",
                nullable = true
        )
        LocalDateTime paidAt,
        @NotEmpty(message = "영수증 품목은 하나 이상이어야 합니다.")
        List<@NotNull @Valid Item> items
) {
    public CreateSettlementReceiptExpenseCommand toCommand() {
        return new CreateSettlementReceiptExpenseCommand(
                receiptReference,
                storeName,
                paidAt,
                items.stream().map(Item::toCommand).toList()
        );
    }

    public record Item(
            @NotBlank(message = "품목명은 필수입니다.")
            @Size(
                    max = SettlementExpense.MAX_DESCRIPTION_LENGTH,
                    message = "품목명은 500자 이하여야 합니다."
            )
            String name,
            @Positive(message = "품목 수량은 1개 이상이어야 합니다.")
            int quantity,
            @Positive(message = "품목 행 금액은 1원 이상이어야 합니다.")
            long amount
    ) {
        private CreateSettlementReceiptExpenseCommand.Item toCommand() {
            return new CreateSettlementReceiptExpenseCommand.Item(name, quantity, amount);
        }
    }
}
