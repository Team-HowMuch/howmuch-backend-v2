package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.LinkBankAccountsCommand;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 연동 Application 흐름")
class BankAccountServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private SaveAccountPort saveAccountPort;
    @Mock private BankConnectionPort bankConnectionPort;
    @Mock private BankCatalog bankCatalog;
    @Mock private User user;

    @Test
    @DisplayName("처음 선택한 계좌 하나만 기본으로 두고 표시 순서를 부여한다")
    void assignsDefaultAndDisplayOrderToNewAccounts() throws Exception {
        Long userId = 1L;
        Long connectionId = 7L;
        String connectedId = "CID-1";
        String organization = "0020";
        var first = candidate("1002123456789", "주거래 통장");
        var second = candidate("1002987654321", "생활비 통장");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
        when(loadUserPort.findById(userId)).thenReturn(Optional.of(user));
        when(connectionStorePort.findActiveByIdAndUserId(connectionId, userId))
                .thenReturn(Optional.of(new ExternalConnection(connectionId, userId, connectedId)));
        when(connectionStorePort.hasInstitution(connectionId, organization)).thenReturn(true);
        when(bankConnectionPort.findAccounts(connectedId, organization, null)).thenReturn(List.of(first, second));
        when(loadAccountPort.findActiveByUserId(userId)).thenReturn(List.of());
        when(saveAccountPort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        BankAccountService service = new BankAccountService(
                currentActorPort, loadUserPort, connectionStorePort, loadAccountPort,
                saveAccountPort, bankConnectionPort, bankCatalog
        );

        service.link(new LinkBankAccountsCommand(
                connectionId,
                organization,
                List.of(
                        candidateKey(connectedId, organization, first.externalAccountKey()),
                        candidateKey(connectedId, organization, second.externalAccountKey())
                ),
                null
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Account>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveAccountPort).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Account::isDefault).containsExactly(true, false);
        assertThat(captor.getValue()).extracting(Account::displayOrder).containsExactly(0, 1);
        assertThat(captor.getValue()).extracting(Account::productCode).containsOnly("11");
    }

    private BankConnectionPort.ExternalAccountCandidate candidate(String key, String name) {
        return new BankConnectionPort.ExternalAccountCandidate(
                key, AccountType.DEPOSIT_TRUST, name, "****" + key.substring(key.length() - 4),
                new BigDecimal("1000000"), "KRW", "11"
        );
    }

    private String candidateKey(String connectedId, String organization, String account) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(connectedId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(organization.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest.digest(account.getBytes(StandardCharsets.UTF_8))
        );
    }
}
