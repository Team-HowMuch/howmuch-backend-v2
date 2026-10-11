package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.BankConnectionQueryDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts/connections")
public class BankConnectionQueryController implements BankConnectionQueryDocs {
    private final GetBankConnectionsUseCase connections;
    private final GetBankConnectionAccountsUseCase candidates;

    @Override
    @GetMapping
    public ApiResponse<List<BankConnectionSummaryWebResponse>> getConnections() {
        return ApiResponse.success(connections.getConnections().stream()
                .map(BankConnectionSummaryWebResponse::from).toList());
    }

    @Override
    @GetMapping("/{connectionId}/accounts")
    public ApiResponse<BankConnectionWebResponse> getConnectionAccounts(
            @PathVariable Long connectionId, @RequestParam String organization,
            @RequestParam(required = false) String birthDate
    ) {
        return ApiResponse.success(BankConnectionWebResponse.from(candidates.getConnectionAccounts(
                new GetBankConnectionAccountsQuery(connectionId, organization, birthDate))));
    }
}
