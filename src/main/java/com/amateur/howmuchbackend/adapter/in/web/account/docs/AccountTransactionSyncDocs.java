package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.AccountTransactionSyncWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountTransactionSyncJobWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "계좌 거래 동기화 API", description = "카나리 계좌의 비동기 거래 동기화 작업 API")
public interface AccountTransactionSyncDocs {
    @Operation(
            summary = "계좌 거래 동기화 작업 접수",
            description = "최대 31일의 CODEF 은행 거래 수집을 비동기 작업으로 접수합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "접수 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_ARGUMENT 또는 INVALID_ACCOUNT_TRANSACTION_SYNC_REQUEST"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT, ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING 또는 ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "ACCOUNT_TRANSACTION_NOT_SUPPORTED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE")
    })
    ApiResponse<AccountTransactionSyncJobWebResponse> request(
            Long accountId,
            @Parameter(required = true, example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8") UUID idempotencyKey,
            AccountTransactionSyncWebRequest request
    );

    @Operation(
            summary = "계좌 거래 동기화 작업 조회",
            description = "현재 사용자가 접수한 거래 동기화 작업의 상태를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_TRANSACTION_SYNC_JOB_NOT_FOUND")
    })
    ApiResponse<AccountTransactionSyncJobWebResponse> get(UUID syncJobId);
}
