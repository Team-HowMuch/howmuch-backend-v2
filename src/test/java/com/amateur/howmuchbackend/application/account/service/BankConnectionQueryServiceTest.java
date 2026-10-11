package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankConnectionSummary;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.LoadBankConnectionsPort;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BankConnectionQueryServiceTest {
    private final CurrentActorPort actor = mock(CurrentActorPort.class);
    private final LoadBankConnectionsPort connections = mock(LoadBankConnectionsPort.class);
    private final LoadAccountPort accounts = mock(LoadAccountPort.class);
    private final BankCatalog catalog = mock(BankCatalog.class);
    private final BankConnectionQueryService service = new BankConnectionQueryService(actor, connections, accounts, catalog);

    @Test
    void noOwnedConnectionsReturnsEmptyWithoutLoadingAccountsOrCatalog() {
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(3L));
        when(connections.findActiveByUserId(3L)).thenReturn(List.of());
        assertThat(service.getConnections()).isEmpty();
        verifyNoInteractions(accounts, catalog);
    }

    @Test
    void countsOnlyAccountsOfTheSameConnectionAndInstitution() {
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(3L));
        var now = LocalDateTime.of(2026, 10, 11, 1, 0);
        when(connections.findActiveByUserId(3L)).thenReturn(List.of(
                new BankConnectionSummary(7L, "0020", ExternalConnectionStatus.CONNECTED, now),
                new BankConnectionSummary(7L, "0088", ExternalConnectionStatus.CONNECTED, now)));
        when(catalog.getAllBanks()).thenReturn(List.of(new BankCatalogResult("0020", "우리은행", "icon", true)));
        var linkedAccounts = List.of(account(7L, "0020"), account(8L, "0020"));
        when(accounts.findActiveByUserId(3L)).thenReturn(linkedAccounts);
        var result = service.getConnections();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().institutionName()).isEqualTo("우리은행");
        assertThat(result.getFirst().linkedAccountCount()).isEqualTo(1);
        assertThat(result.getFirst().connectedAt()).isEqualTo(now);
        assertThat(result.get(1).institutionName()).isEqualTo("0088");
        assertThat(result.get(1).linkedAccountCount()).isZero();
    }

    private Account account(Long connectionId, String bank) {
        Account account = mock(Account.class);
        when(account.externalConnectionId()).thenReturn(connectionId);
        if (connectionId.equals(7L)) when(account.organization()).thenReturn(bank);
        return account;
    }
}
