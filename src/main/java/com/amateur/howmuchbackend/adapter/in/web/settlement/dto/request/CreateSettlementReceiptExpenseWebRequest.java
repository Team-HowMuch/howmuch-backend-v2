package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
        List<@NotNull @Valid Item> items,
        @PositiveOrZero(message = "영수증 전체 할인은 0원 이상이어야 합니다.")
        Long discount,
        @PositiveOrZero(message = "배달비는 0원 이상이어야 합니다.")
        @Schema(description = "배달팁 할인을 반영한 순 배달비. 생략하거나 null이면 0원이며 전체 할인 상한에는 포함하지 않습니다.",
                example = "3000")
        Long deliveryFee
) {
    public CreateSettlementReceiptExpenseWebRequest(
            String receiptReference, String storeName, LocalDateTime paidAt,
            List<Item> items, Long discount
    ) {
        this(receiptReference, storeName, paidAt, items, discount, null);
    }

    public CreateSettlementReceiptExpenseWebRequest(
            String receiptReference, String storeName, LocalDateTime paidAt, List<Item> items
    ) {
        this(receiptReference, storeName, paidAt, items, null);
    }

    public CreateSettlementReceiptExpenseCommand toCommand() {
        return new CreateSettlementReceiptExpenseCommand(
                receiptReference,
                storeName,
                paidAt,
                items.stream().map(Item::toCommand).toList(),
                discount == null ? 0 : discount,
                deliveryFee == null ? 0 : deliveryFee
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
            @PositiveOrZero(message = "품목 행 금액은 0원 이상이어야 합니다.")
            long amount,
            @PositiveOrZero(message = "품목 할인은 0원 이상이어야 합니다.")
            Long discount,
            List<@NotNull @Valid Option> options
    ) {
        public Item(String name, int quantity, long amount) {
            this(name, quantity, amount, null, List.of());
        }

        CreateSettlementReceiptExpenseCommand.Item toCommand() {
            return new CreateSettlementReceiptExpenseCommand.Item(
                    name, quantity, amount, discount == null ? 0 : discount,
                    options == null ? List.of() : options.stream().map(Option::toCommand).toList()
            );
        }
    }

    public record Option(
            @NotBlank(message = "옵션명은 필수입니다.")
            @Size(max = 500, message = "옵션명은 500자 이하여야 합니다.")
            String name,
            @PositiveOrZero(message = "옵션 가격은 0원 이상이어야 합니다.")
            long price
    ) {
        private CreateSettlementReceiptExpenseCommand.Option toCommand() {
            return new CreateSettlementReceiptExpenseCommand.Option(name, price);
        }
    }
}
