package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.AccountTransactionSyncDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.AccountTransactionSyncWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountTransactionSyncJobWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionSyncJobUseCase;
import com.amateur.howmuchbackend.application.port.in.account.RequestAccountTransactionSyncUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AccountTransactionSyncController implements AccountTransactionSyncDocs {
    private final RequestAccountTransactionSyncUseCase requestUseCase;
    private final GetAccountTransactionSyncJobUseCase getUseCase;

    @Override
    @PostMapping("/api/v2/accounts/{accountId}/transaction-sync-jobs")
    public ApiResponse<AccountTransactionSyncJobWebResponse> request(
            @PathVariable Long accountId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody AccountTransactionSyncWebRequest request
    ) {
        return ApiResponse.of(
                202,
                AccountTransactionSyncJobWebResponse.from(
                        requestUseCase.request(request.toCommand(accountId, idempotencyKey))
                )
        );
    }

    @Override
    @GetMapping("/api/v2/account-transaction-sync-jobs/{syncJobId}")
    public ApiResponse<AccountTransactionSyncJobWebResponse> get(@PathVariable UUID syncJobId) {
        return ApiResponse.success(AccountTransactionSyncJobWebResponse.from(getUseCase.get(syncJobId)));
    }
}
