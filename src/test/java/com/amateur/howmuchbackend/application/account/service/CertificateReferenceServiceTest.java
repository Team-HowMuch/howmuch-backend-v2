package com.amateur.howmuchbackend.application.account.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.policy.CertificateReferenceInputPolicy;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.application.port.out.account.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateInstitutionCatalogPort;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.*;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class CertificateReferenceServiceTest {
    final CurrentActorPort actor = mock(CurrentActorPort.class);
    final LoadUserPort users = mock(LoadUserPort.class);
    final ExternalConnectionStorePort connections = mock(ExternalConnectionStorePort.class);
    final CertificateReferenceOperationStorePort operations =
            mock(CertificateReferenceOperationStorePort.class);
    final CertificateReferenceProtectionPort protection =
            mock(CertificateReferenceProtectionPort.class);
    final BankCertificateReferencePort provider = mock(BankCertificateReferencePort.class);
    final InstitutionAuthenticationStorePort authentication =
            mock(InstitutionAuthenticationStorePort.class);
    final BankCatalog banks = mock(BankCatalog.class);
    final FinancialConnectionMutationGuard guard = mock(FinancialConnectionMutationGuard.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
    final CertificateInstitutionCatalogPort catalog =
            () ->
                    List.of(
                            new CertificateInstitutionResult("0020", "우리은행", true, false, null, 3),
                            new CertificateInstitutionResult("0088", "신한은행", true, false, null, 1));
    final CertificateReferenceService service =
            new CertificateReferenceService(
                    actor,
                    users,
                    connections,
                    operations,
                    protection,
                    authentication,
                    catalog,
                    banks,
                    new CertificateReferenceInputPolicy(),
                    guard,
                    clock);

    void activeUser() {
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        var user = mock(User.class);
        when(user.active()).thenReturn(true);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }

    StartCertificateReferenceConnectionCommand command(UUID key) {
        return new StartCertificateReferenceConnectionCommand(
                15, key, "0020", "0088", null, null, null);
    }

    CertificateReferenceOperation op(UUID key, String hash) {
        return new CertificateReferenceOperation(
                UUID.randomUUID(),
                1,
                15,
                "0020",
                "0088",
                key,
                hash,
                "b".repeat(64),
                CertificateReferenceStatus.QUEUED,
                false,
                null,
                LocalDateTime.now(clock),
                LocalDateTime.now(clock).plusMinutes(10),
                null,
                null);
    }

    @Test
    void admissionDoesNotCallCodefAndAtomicallyCreatesProtectedWork() {
        activeUser();
        var c = command(UUID.randomUUID());
        when(connections.findActiveByIdAndUserId(15L, 1L))
                .thenReturn(Optional.of(new ExternalConnection(15L, 1L, "fixture")));
        when(protection.requestHash(1, c)).thenReturn("a".repeat(64));
        when(protection.connectionHash(1, "fixture")).thenReturn("b".repeat(64));
        when(operations.create(any(), any())).thenReturn(true);
        when(protection.sealReference(any(), any()))
                .thenReturn(new CertificateEnvelope(new byte[29], new byte[32], "local:v1"));
        var result = service.startCertificateReference(c);
        assertThat(result.status()).isEqualTo(CertificateReferenceStatus.QUEUED);
        verify(operations).create(any(), any());
        verify(guard).requireAvailable(1);
        verifyNoInteractions(provider);
    }

    @Test
    void replayReturnsSameOperationWithoutProviderOrNewProtection() {
        activeUser();
        var c = command(UUID.randomUUID());
        var existing = op(c.idempotencyKey(), "same");
        when(protection.requestHash(1, c)).thenReturn("same");
        when(operations.findByKey(1, c.idempotencyKey())).thenReturn(Optional.of(existing));
        assertThat(service.startCertificateReference(c).operationId()).isEqualTo(existing.id());
        verifyNoInteractions(provider, connections, guard);
        verify(protection, never()).sealReference(any(), any());
        verify(operations, never()).create(any(), any());
    }

    @Test
    void changedInputWithSameKeyIsRejected() {
        activeUser();
        var c = command(UUID.randomUUID());
        when(protection.requestHash(1, c)).thenReturn("changed");
        when(operations.findByKey(1, c.idempotencyKey()))
                .thenReturn(Optional.of(op(c.idempotencyKey(), "original")));
        assertThatThrownBy(() -> service.startCertificateReference(c))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .CERTIFICATE_REFERENCE_IDEMPOTENCY_CONFLICT));
        verifyNoInteractions(provider, guard);
    }

    @Test
    void foreignConnectionNeverCreatesWorkOrCallsProvider() {
        activeUser();
        assertThatThrownBy(() -> service.startCertificateReference(command(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .EXTERNAL_CONNECTION_NOT_FOUND));
        verify(operations, never()).create(any(), any());
        verifyNoInteractions(provider);
    }

    @Test
    void foreignOperationDoesNotLeakItsExistence() {
        activeUser();
        assertThatThrownBy(() -> service.getCertificateReferenceOperation(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .CERTIFICATE_REFERENCE_NOT_FOUND));
    }

    @Test
    void candidatesUseOnlyLocallyRecordedCertificateSourcesWithoutCodefCalls() {
        activeUser();
        when(connections.findActiveByUserId(1L))
                .thenReturn(Optional.of(new ExternalConnection(15L, 1L, "fixture")));
        when(authentication.findCertificateOrganizations(15L))
                .thenReturn(List.of("0020", "0088", "0099"));
        when(banks.getAllBanks())
                .thenReturn(
                        List.of(
                                new com.amateur.howmuchbackend.application.port.in.account.result
                                        .BankCatalogResult("0020", "우리은행", "icon", 3),
                                new com.amateur.howmuchbackend.application.port.in.account.result
                                        .BankCatalogResult("0088", "신한은행", "icon", 1),
                                new com.amateur.howmuchbackend.application.port.in.account.result
                                        .BankCatalogResult("0004", "국민은행", "icon", 2)));
        var result = service.getCertificateReferences("0088");
        assertThat(result.references())
                .extracting(r -> r.sourceOrganization())
                .containsExactly("0020");
        assertThat(result.authenticationLevel()).isEqualTo(1);
        verifyNoInteractions(provider);
        verify(authentication).findCertificateOrganizations(15L);
    }

    @Test
    void levelThreeRequiresTargetAccountCredentialsBeforeAdmission() {
        activeUser();
        var c =
                new StartCertificateReferenceConnectionCommand(
                        15, UUID.randomUUID(), "0088", "0020", null, null, null);
        assertThatThrownBy(() -> service.startCertificateReference(c))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .BANK_ACCOUNT_CREDENTIAL_REQUIRED));
        verify(operations, never()).create(any(), any());
        verifyNoInteractions(provider);
    }

    @Test
    void fullCapacityReturnsRetryableBusyInsteadOfAcceptingAnExpiringJob() {
        activeUser();
        var command = command(UUID.randomUUID());
        when(connections.findActiveByIdAndUserId(15L, 1L))
                .thenReturn(Optional.of(new ExternalConnection(15L, 1L, "fixture")));
        when(operations.create(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.startCertificateReference(command))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .CERTIFICATE_REFERENCE_BUSY));
        verifyNoInteractions(provider);
    }

}
