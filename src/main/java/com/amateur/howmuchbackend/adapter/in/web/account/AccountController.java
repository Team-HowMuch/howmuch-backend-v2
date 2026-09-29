package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.AccountDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ReplaceAccountDisplayOrdersWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.UpdateAccountDisplayOrderWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.LinkedAccountsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountTransactionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountDetailUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.ReplaceAccountDisplayOrdersUseCase;
import com.amateur.howmuchbackend.application.port.in.account.SetDefaultAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.UpdateAccountDisplayOrderUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts")
public class AccountController implements AccountDocs {
    private final GetBankCatalogUseCase getBankCatalogUseCase;
    private final ConnectBankUseCase connectBankUseCase;
    private final LinkBankAccountsUseCase linkBankAccountsUseCase;
    private final GetAccountsUseCase getAccountsUseCase;
    private final GetAccountDetailUseCase getAccountDetailUseCase;
    private final GetAccountTransactionsUseCase getAccountTransactionsUseCase;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final SetDefaultAccountUseCase setDefaultAccountUseCase;
    private final UpdateAccountDisplayOrderUseCase updateAccountDisplayOrderUseCase;
    private final ReplaceAccountDisplayOrdersUseCase replaceAccountDisplayOrdersUseCase;

    @Override
    @GetMapping
    public ApiResponse<List<AccountSummaryWebResponse>> getAccounts(
            @RequestParam(required = false) String organization,
            @RequestParam(defaultValue = "DEFAULT") AccountSort sort
    ) {
        return ApiResponse.success(getAccountsUseCase.getAccounts(new GetAccountsQuery(organization, sort)).stream()
                .map(AccountSummaryWebResponse::from)
                .toList());
    }

    @Override
    @GetMapping("/{accountId}")
    public ApiResponse<AccountDetailWebResponse> getAccountDetail(
            @PathVariable Long accountId,
            @RequestParam(required = false) YearMonth yearMonth
    ) {
        return ApiResponse.success(AccountDetailWebResponse.from(
                getAccountDetailUseCase.getAccountDetail(accountId, yearMonth)
        ));
    }

    @Override
    @GetMapping("/{accountId}/transactions")
    public ApiResponse<List<AccountTransactionWebResponse>> getAccountTransactions(
            @PathVariable Long accountId,
            @RequestParam(required = false) YearMonth yearMonth
    ) {
        return ApiResponse.success(getAccountTransactionsUseCase.getAccountTransactions(accountId, yearMonth).stream()
                .map(AccountTransactionWebResponse::from)
                .toList());
    }

    @Override
    @DeleteMapping("/{accountId}")
    public ApiResponse<Void> deleteAccount(@PathVariable Long accountId) {
        deleteAccountUseCase.delete(accountId);
        return ApiResponse.success();
    }

    @Override
    @PatchMapping("/{accountId}/default")
    public ApiResponse<Void> setDefaultAccount(@PathVariable Long accountId) {
        setDefaultAccountUseCase.setDefault(accountId);
        return ApiResponse.success();
    }

    @Override
    @PatchMapping("/display-order")
    public ApiResponse<Void> replaceAccountDisplayOrders(
            @RequestBody ReplaceAccountDisplayOrdersWebRequest request
    ) {
        replaceAccountDisplayOrdersUseCase.replaceDisplayOrders(request.toCommand());
        return ApiResponse.success();
    }

    @Override
    @PatchMapping("/{accountId}/display-order")
    public ApiResponse<Void> updateAccountDisplayOrder(
            @PathVariable Long accountId,
            @RequestBody UpdateAccountDisplayOrderWebRequest request
    ) {
        updateAccountDisplayOrderUseCase.updateDisplayOrder(request.toCommand(accountId));
        return ApiResponse.success();
    }

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
            @RequestBody LinkBankAccountsWebRequest request
    ) {
        return ApiResponse.success(LinkedAccountsWebResponse.from(linkBankAccountsUseCase.link(request.toCommand())));
    }
}
