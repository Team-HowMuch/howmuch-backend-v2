package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import com.amateur.howmuchbackend.application.port.out.account.AccountStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 연동 Application 흐름")
class BankAccountServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private AccountStorePort accountStorePort;
    @Mock private BankConnectionPort bankConnectionPort;
    @Mock private BankCatalog bankCatalog;
    @Mock private User user;

    @Test
    @DisplayName("이미 연결된 은행은 CODEF 계정 수정 후 계좌 후보를 재조회한다")
    void updatesCredentialsForExistingInstitution() {
        Long userId = 1L;
        Long connectionId = 7L;
        String connectedId = "CID-1";
        String organization = "0020";
        var registration = new BankConnectionPort.BankRegistration(organization, "yong", "password", null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
        when(loadUserPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(connectionStorePort.findActiveByUserId(userId))
                .thenReturn(Optional.of(new ExternalConnection(connectionId, userId, connectedId)));
        when(connectionStorePort.hasInstitution(connectionId, organization)).thenReturn(true);
        when(accountStorePort.findCandidateKeys(connectionId)).thenReturn(Set.of());
        when(bankConnectionPort.findAccounts(connectedId, organization, null))
                .thenReturn(List.of(candidate("2002123456789", "yong 통장")));
        BankAccountService service = new BankAccountService(
                currentActorPort, loadUserPort, connectionStorePort, accountStorePort,
                bankConnectionPort, bankCatalog
        );

        var result = service.connect(new ConnectBankCommand(organization, "yong", "password", null));

        verify(bankConnectionPort).update(connectedId, registration);
        assertThat(result.candidates()).extracting(candidate -> candidate.name()).containsExactly("yong 통장");
    }

    private BankConnectionPort.ExternalAccountCandidate candidate(String key, String name) {
        return new BankConnectionPort.ExternalAccountCandidate(
                key, AccountType.DEPOSIT_TRUST, name, "****" + key.substring(key.length() - 4),
                new BigDecimal("1000000"), "KRW"
        );
    }
}
