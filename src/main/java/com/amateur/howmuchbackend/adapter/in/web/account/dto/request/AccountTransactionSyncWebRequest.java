package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.RequestAccountTransactionSyncCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record AccountTransactionSyncWebRequest(
        @NotNull(message = "동기화 시작일은 필수입니다.")
        @Schema(example = "2026-08-01")
        LocalDate fromDate,

        @NotNull(message = "동기화 종료일은 필수입니다.")
        @Schema(example = "2026-08-31")
        LocalDate toDate
) {
    public RequestAccountTransactionSyncCommand toCommand(Long accountId, UUID idempotencyKey) {
        return new RequestAccountTransactionSyncCommand(accountId, idempotencyKey, fromDate, toDate);
    }
}
