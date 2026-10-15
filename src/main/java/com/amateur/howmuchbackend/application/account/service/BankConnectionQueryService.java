package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.LoadBankConnectionsPort;
import com.amateur.howmuchbackend.domain.account.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BankConnectionQueryService implements GetBankConnectionsUseCase {
    private final CurrentActorPort currentActor;
    private final LoadBankConnectionsPort connections;
    private final LoadAccountPort accounts;
    private final BankCatalog catalog;

    @Override
    @Transactional(readOnly = true)
    public List<BankConnectionSummaryResult> getConnections() {
        Long userId = currentActor.getCurrentActor().userId();
        var summaries = connections.findActiveByUserId(userId);
        if (summaries.isEmpty()) return List.of();
        var banks = catalog.getAllBanks().stream()
                .collect(Collectors.toMap(BankCatalogResult::organization, Function.identity()));
        List<Account> linkedAccounts = accounts.findActiveByUserId(userId);
        return summaries.stream().map(connection -> {
            var bank = banks.get(connection.organization());
            long count = linkedAccounts.stream().filter(account ->
                    connection.connectionId().equals(account.externalConnectionId())
                            && connection.organization().equals(account.organization())).count();
            return new BankConnectionSummaryResult(
                    connection.connectionId(), connection.organization(),
                    bank == null ? connection.organization() : bank.name(),
                    bank == null ? null : bank.iconUrl(), connection.status(), connection.connectedAt(), count);
        }).toList();
    }
}
