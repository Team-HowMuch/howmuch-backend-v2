package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.AccountDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.LinkedAccountsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts")
public class AccountController implements AccountDocs {
    private final ConnectBankUseCase connectBankUseCase;
    private final LinkBankAccountsUseCase linkBankAccountsUseCase;

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
