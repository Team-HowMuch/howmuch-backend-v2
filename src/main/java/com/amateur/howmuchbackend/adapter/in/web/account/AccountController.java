package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.AccountDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.LinkedAccountsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts")
public class AccountController implements AccountDocs {
    private final GetBankCatalogUseCase getBankCatalogUseCase;
    private final ConnectBankUseCase connectBankUseCase;
    private final LinkBankAccountsUseCase linkBankAccountsUseCase;

    @Override
    @GetMapping("/banks")
    public ApiResponse<List<BankCatalogWebResponse>> getBanks() {
        return ApiResponse.success(getBankCatalogUseCase.getBanks().stream()
                .map(BankCatalogWebResponse::from)
                .toList());
    }

    @Override
    @PostMapping("/connections/banks")
    public ApiResponse<BankConnectionWebResponse> connectBank(
            @Valid @RequestBody ConnectBankWebRequest request
    ) {
        return ApiResponse.success(BankConnectionWebResponse.from(connectBankUseCase.connect(request.toCommand())));
    }

    @Override
    @PostMapping("/external")
    public ApiResponse<LinkedAccountsWebResponse> linkAccounts(
            @Valid @RequestBody LinkBankAccountsWebRequest request
    ) {
        return ApiResponse.success(LinkedAccountsWebResponse.from(linkBankAccountsUseCase.link(request.toCommand())));
    }
}
