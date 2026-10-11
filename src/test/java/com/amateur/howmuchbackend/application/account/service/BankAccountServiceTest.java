package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.EncryptedBankAccountCredentials;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
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
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private AccountCredentialProtectionService credentialProtection;
    @Mock private User user;

    @Test
    void requiredCredentialsMustBePresentBeforeCodefCall() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(bankCatalog.requiresAccountCredentials("0020")).thenReturn(true);

        assertThatThrownBy(() -> service().connect(
                new ConnectBankCommand("0020", "login", "password", null, "123-456", null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_REQUIRED));
        verifyNoInteractions(bankConnectionPort, credentialProtection);
    }

    @Test
    void requiredCredentialsAreStoredForNewConnection() {
        var credentials = new BankAccountCredentials("123-456", "1234");
        var encrypted = new EncryptedBankAccountCredentials("local:v1:value", "kms:v1:value");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(bankCatalog.requiresAccountCredentials("0020")).thenReturn(true);
        when(credentialProtection.protect(1L, "0020", credentials)).thenReturn(encrypted);
        when(bankConnectionPort.create(any())).thenReturn(new BankConnectionPort.ConnectionResult("CID-1", "CF-00000"));
        when(connectionStorePort.saveNew(any(), any())).thenReturn(new ExternalConnection(7L, 1L, "CID-1"));
        when(loadAccountPort.findCandidateKeys(7L)).thenReturn(Set.of());

        service().connect(new ConnectBankCommand("0020", "login", "password", null, "123-456", "1234"));

        verify(bankConnectionPort).create(new BankConnectionPort.BankRegistration(
                "0020", "login", "password", null, credentials));
        verify(connectionStorePort).saveNew(any(), org.mockito.ArgumentMatchers.argThat(institution ->
                "local:v1:value".equals(institution.localCredentialsCiphertext())
                        && "kms:v1:value".equals(institution.kmsCredentialsCiphertext())));
        verify(bankConnectionPort).findAccounts("CID-1", "0020", null, credentials);
    }

    @Test
    void linkUsesStoredCredentialsForRequiredBank() throws Exception {
        Long userId = 1L;
        Long connectionId = 7L;
        var credentials = new BankAccountCredentials("123-456", "1234");
        var candidate = candidate("1002123456789", "주거래 통장");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(connectionStorePort.findActiveByIdAndUserId(connectionId, userId))
                .thenReturn(Optional.of(new ExternalConnection(connectionId, userId, "CID-1")));
        when(connectionStorePort.hasInstitution(connectionId, "0020")).thenReturn(true);
        when(bankCatalog.requiresAccountCredentials("0020")).thenReturn(true);
        when(credentialProtection.reveal(userId, connectionId, "0020")).thenReturn(credentials);
        when(bankConnectionPort.findAccounts("CID-1", "0020", null, credentials)).thenReturn(List.of(candidate));
        when(loadAccountPort.findActiveByUserId(userId)).thenReturn(List.of());
        when(saveAccountPort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service().link(new LinkBankAccountsCommand(connectionId, "0020",
                List.of(candidateKey("CID-1", "0020", candidate.externalAccountKey())), null));

        verify(bankConnectionPort).findAccounts("CID-1", "0020", null, credentials);
    }

    @Test
    @DisplayName("이미 연결된 은행은 CODEF 계정 수정 후 계좌 후보를 재조회한다")
    void bankCredentials_existingBank_updatedAndRefetchesAccounts() {
        Long userId = 1L;
        Long connectionId = 7L;
        String connectedId = "CID-1";
        String organization = "0020";
        var registration = new BankConnectionPort.BankRegistration(organization, "yong", "password", null, null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(connectionStorePort.findActiveByUserId(userId))
                .thenReturn(Optional.of(new ExternalConnection(connectionId, userId, connectedId)));
        when(connectionStorePort.hasInstitution(connectionId, organization)).thenReturn(true);
        when(bankConnectionPort.registeredOrganizations(connectedId)).thenReturn(Set.of(organization));
        when(loadAccountPort.findCandidateKeys(connectionId)).thenReturn(Set.of());
        when(bankConnectionPort.findAccounts(connectedId, organization, null, null))
                .thenReturn(List.of(candidate("2002123456789", "yong 통장")));

        var result = service().connect(new ConnectBankCommand(organization, "yong", "password", null, null, null));

        verify(bankConnectionPort).update(connectedId, registration);
        assertThat(result.candidates()).extracting(candidate -> candidate.name()).containsExactly("yong 통장");
    }

    @Test
    @DisplayName("처음 선택한 계좌 하나만 기본으로 두고 표시 순서를 부여한다")
    void newBankAccounts_oneDefaultAssignedAndOrderSet() throws Exception {
        Long userId = 1L;
        Long connectionId = 7L;
        String connectedId = "CID-1";
        String organization = "0020";
        var first = candidate("1002123456789", "주거래 통장");
        var second = candidate("1002987654321", "생활비 통장");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(connectionStorePort.findActiveByIdAndUserId(connectionId, userId))
                .thenReturn(Optional.of(new ExternalConnection(connectionId, userId, connectedId)));
        when(connectionStorePort.hasInstitution(connectionId, organization)).thenReturn(true);
        when(bankConnectionPort.findAccounts(connectedId, organization, null, null)).thenReturn(List.of(first, second));
        when(loadAccountPort.findActiveByUserId(userId)).thenReturn(List.of());
        when(saveAccountPort.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service().link(new LinkBankAccountsCommand(
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

    @Test
    void existingConnectionCandidatesRejectOtherUsersBeforeProviderCalls() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(connectionStorePort.findActiveByUserId(1L)).thenReturn(Optional.of(new ExternalConnection(7L, 1L, "CID-1")));
        assertThatThrownBy(() -> service().getConnectionAccounts(query(8L, "0020")))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND));
        verifyNoInteractions(bankConnectionPort, credentialProtection, loadAccountPort);
    }

    @Test
    void inactiveOrUnboundConnectionCannotQueryAccounts() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(connectionStorePort.findActiveByUserId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getConnectionAccounts(query(7L, "0020")))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND));
        verifyNoInteractions(bankConnectionPort, credentialProtection);
    }

    @Test
    void unregisteredInstitutionCannotUseOwnedConnection() {
        ownedConnection();
        assertThatThrownBy(() -> service().getConnectionAccounts(query(7L, "0088")))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(ExternalConnectionStatusCode.ACCOUNT_CANDIDATE_INVALID));
        verifyNoInteractions(bankConnectionPort, credentialProtection);
    }

    @Test
    void existingConnectionReturnsCompatibleCandidateKeysWithStoredCredentials() throws Exception {
        ownedConnection();
        when(connectionStorePort.hasInstitution(7L, "0020")).thenReturn(true);
        when(bankCatalog.requiresAccountCredentials("0020")).thenReturn(true);
        var credentials = new BankAccountCredentials("fixture-number", "fixture-password");
        when(credentialProtection.reveal(1L, 7L, "0020")).thenReturn(credentials);
        String key = candidateKey("CID-1", "0020", "12345678");
        when(loadAccountPort.findCandidateKeys(7L)).thenReturn(Set.of(key));
        when(bankConnectionPort.findAccounts("CID-1", "0020", "19990101", credentials))
                .thenReturn(List.of(candidate("12345678", "fixture")));
        var result = service().getConnectionAccounts(query(7L, "0020"));
        assertThat(result.connectionId()).isEqualTo(7L);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.candidateKey()).isEqualTo(key);
            assertThat(candidate.alreadyLinked()).isTrue();
        });
        verifyNoInteractions(saveAccountPort, loadUserPort);
    }

    @Test
    void missingStoredCredentialsStopBeforeCodefCall() {
        ownedConnection();
        when(connectionStorePort.hasInstitution(7L, "0020")).thenReturn(true);
        when(bankCatalog.requiresAccountCredentials("0020")).thenReturn(true);
        when(credentialProtection.reveal(1L, 7L, "0020")).thenThrow(
                ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED));
        assertThatThrownBy(() -> service().getConnectionAccounts(query(7L, "0020")))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED));
        verifyNoInteractions(bankConnectionPort);
    }

    private void ownedConnection() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(connectionStorePort.findActiveByUserId(1L)).thenReturn(Optional.of(new ExternalConnection(7L, 1L, "CID-1")));
    }

    private com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery query(
            Long connectionId, String organization
    ) {
        return new com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery(
                connectionId, organization, "19990101");
    }

    private BankAccountService service() {
        var registrationService = new BankConnectionRegistrationService(
                currentActorPort, loadUserPort, connectionStorePort, saveAccountPort,
                bankConnectionPort, bankCatalog, credentialProtection
        );
        return new BankAccountService(
                currentActorPort, loadUserPort, connectionStorePort, loadAccountPort,
                saveAccountPort, bankConnectionPort, bankCatalog, credentialProtection, registrationService
        );
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
