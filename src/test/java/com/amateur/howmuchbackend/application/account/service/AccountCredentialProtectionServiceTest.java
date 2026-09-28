package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.TemporaryKmsUnavailableException;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.external.KmsAccountCredentialCipherPort;
import com.amateur.howmuchbackend.application.port.out.external.LocalAccountCredentialCipherPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountCredentialProtectionServiceTest {
    private static final BankAccountCredentials CREDENTIALS = new BankAccountCredentials("123-456", "1234");
    @Mock private LocalAccountCredentialCipherPort local;
    @Mock private KmsAccountCredentialCipherPort kms;
    @Mock private ExternalConnectionStorePort store;

    @Test
    void temporaryKmsFailureKeepsLocalAndBackfillsAfterRecovery() {
        when(local.encrypt(1L, "0020", CREDENTIALS)).thenReturn("local:v1:value");
        when(kms.enabled()).thenReturn(true);
        when(kms.encrypt(1L, "0020", CREDENTIALS))
                .thenThrow(new TemporaryKmsUnavailableException(new RuntimeException()))
                .thenReturn("kms:v1:value");

        var encrypted = service().protect(1L, "0020", CREDENTIALS);
        assertThat(encrypted.localCiphertext()).isEqualTo("local:v1:value");
        assertThat(encrypted.kmsCiphertext()).isNull();

        when(store.findInstitution(7L, "0020")).thenReturn(Optional.of(institution("local:v1:value", null)));
        when(local.decrypt(1L, "0020", "local:v1:value")).thenReturn(CREDENTIALS);
        assertThat(service().reveal(1L, 7L, "0020")).isEqualTo(CREDENTIALS);
        verify(store).saveKmsCredentialsIfAbsent(7L, "0020", "kms:v1:value");
    }

    @Test
    void temporaryKmsDecryptFailureUsesLocalCiphertext() {
        when(kms.enabled()).thenReturn(true);
        when(store.findInstitution(7L, "0020"))
                .thenReturn(Optional.of(institution("local:v1:value", "kms:v1:value")));
        when(kms.decrypt(1L, "0020", "kms:v1:value"))
                .thenThrow(new TemporaryKmsUnavailableException(new RuntimeException()));
        when(local.decrypt(1L, "0020", "local:v1:value")).thenReturn(CREDENTIALS);

        assertThat(service().reveal(1L, 7L, "0020")).isEqualTo(CREDENTIALS);
    }

    @Test
    void invalidKmsCiphertextDoesNotFallback() {
        when(kms.enabled()).thenReturn(true);
        when(store.findInstitution(7L, "0020"))
                .thenReturn(Optional.of(institution("local:v1:value", "kms:v1:value")));
        when(kms.decrypt(1L, "0020", "kms:v1:value"))
                .thenThrow(ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID));

        assertThatThrownBy(() -> service().reveal(1L, 7L, "0020"))
                .isInstanceOf(ApplicationException.class);
        verify(local, never()).decrypt(1L, "0020", "local:v1:value");
    }

    private ConnectedInstitution institution(String localCiphertext, String kmsCiphertext) {
        return new ConnectedInstitution(3L, 7L, "0020", ExternalConnectionStatus.CONNECTED, null,
                localCiphertext, kmsCiphertext);
    }

    private AccountCredentialProtectionService service() {
        return new AccountCredentialProtectionService(local, kms, store);
    }
}
