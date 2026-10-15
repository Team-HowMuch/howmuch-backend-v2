package com.amateur.howmuchbackend.application.certificate;

import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.EncryptedBankAccountCredentials;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.certificate.service.CertificateApprovedRegistrationTransactions;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.user.User;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.*;

class CertificateApprovedRegistrationTransactionsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void certificateRefreshOnlyReplacesAccountCredentialsWhenProvided(boolean replace) {
        var sessions = mock(CertificateSessionStorePort.class);
        var consents = mock(CertificateConsentStorePort.class);
        var users = mock(LoadUserPort.class);
        var connections = mock(ExternalConnectionStorePort.class);
        var banks = mock(BankConnectionPort.class);
        var provider = mock(CertificateProviderAccountPort.class);
        var credentials = mock(AccountCredentialProtectionService.class);
        var protection = mock(CertificateSecretProtectionPort.class);
        var jobs = mock(CertificateExecutionStorePort.class);
        var now = LocalDateTime.of(2026, 10, 10, 0, 0);
        var clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var id = UUID.randomUUID();
        var upload = UUID.randomUUID();
        var command =
                new ReceiveCertificateCommand(
                        UUID.randomUUID(),
                        id,
                        1,
                        "REGISTER",
                        "0004",
                        upload,
                        now.plusMinutes(10),
                        now);
        var execution = new CertificateExecution(command, UUID.randomUUID(), 1);
        var session =
                new CertificateSession(
                        id,
                        1,
                        "0004",
                        "REGISTERING",
                        null,
                        upload,
                        null,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        now.plusMinutes(10),
                        null);
        var consent =
                new CertificateConsent(UUID.randomUUID(), id, UUID.randomUUID(), 11L, 1, now, null);
        var user = mock(User.class);
        var connection = new ExternalConnection(7L, 11L, "connected-fixture");
        when(sessions.find(id, true)).thenReturn(Optional.of(session));
        when(consents.find(id)).thenReturn(Optional.of(consent));
        when(users.findByIdForUpdate(11L)).thenReturn(Optional.of(user));
        when(user.active()).thenReturn(true);
        when(connections.findActiveByUserId(11L)).thenReturn(Optional.of(connection));
        when(banks.registeredOrganizations("connected-fixture")).thenReturn(Set.of("0004"));
        when(connections.hasInstitution(7L, "0004")).thenReturn(true);
        when(provider.update(eq("connected-fixture"), eq("0004"), any(), eq(false)))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        when(protection.sealConnection(eq(id), any(UUID.class), any()))
                .thenReturn(new CertificateEnvelope(new byte[] {3}, new byte[] {4}, "fixture"));
        if (replace) {
            when(credentials.protect(eq(11L), eq("0004"), any()))
                    .thenReturn(new EncryptedBankAccountCredentials("replacement-local", null));
        }
        var service =
                new CertificateApprovedRegistrationTransactions(
                        sessions,
                        consents,
                        users,
                        connections,
                        banks,
                        provider,
                        credentials,
                        protection,
                        jobs,
                        clock,
                        mock(
                                com.amateur.howmuchbackend.application.port.out.certificate
                                        .CertificatePairingStorePort.class));

        try (var material =
                new CertificateMaterial(
                        new byte[] {1},
                        new byte[] {2},
                        "synthetic-password",
                        replace ? "123456" : null,
                        replace ? "1234" : null,
                        null)) {
            service.register(execution, material, false);
            verify(provider).update("connected-fixture", "0004", material, false);
        }

        if (replace) {
            verify(credentials).protect(11L, "0004", new BankAccountCredentials("123456", "1234"));
            verify(connections).updateInstitutionCredentials(7L, "0004", "replacement-local", null);
        } else {
            verifyNoInteractions(credentials);
            verify(connections, never())
                    .updateInstitutionCredentials(
                            anyLong(), anyString(), nullable(String.class), nullable(String.class));
        }
        verify(connections, never()).saveNew(any(), any());
        verify(connections, never()).saveInstitution(any());
        verify(jobs)
                .finish(eq(execution), eq("SUCCEEDED"), isNull(), any(UUID.class), any(), eq(now));
        verify(consents).bind(eq(consent), any(UUID.class), eq(7L), eq(now));
    }
}
