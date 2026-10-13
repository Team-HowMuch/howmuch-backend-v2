package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementReceiptExpenseCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementReceiptExpenseWebRequest.Item;

public record ReplaceSettlementReceiptExpenseWebRequest(
        @NotBlank @Size(max = 500) String storeName,
        @Schema(description = "UTC 결제 시각. null이면 기존 결제일을 지웁니다.") LocalDateTime paidAt,
        @NotEmpty List<@NotNull @Valid Item> items,
        @PositiveOrZero Long discount,
        @PositiveOrZero Long deliveryFee
) {
    public ReplaceSettlementReceiptExpenseCommand toCommand() {
        return new ReplaceSettlementReceiptExpenseCommand(storeName, paidAt,
                items.stream().map(CreateSettlementReceiptExpenseWebRequest.Item::toCommand).toList(),
                discount == null ? 0 : discount, deliveryFee == null ? 0 : deliveryFee);
    }
}
