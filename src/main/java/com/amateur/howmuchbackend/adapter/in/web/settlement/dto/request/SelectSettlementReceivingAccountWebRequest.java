package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SelectSettlementReceivingAccountWebRequest(
        @NotNull
        @Positive
        @Schema(description = "정산 생성자가 송금받을 계좌 ID", example = "1")
        Long receivingAccountId
) {
    public SelectSettlementReceivingAccountCommand toCommand() {
        return new SelectSettlementReceivingAccountCommand(receivingAccountId);
    }
}
