package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.TemporaryKmsUnavailableException;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KmsBankAccountCredentialCipherTest {
    @Mock private ObjectProvider<KmsClient> provider;
    @Mock private KmsClient client;
    private final BankAccountCredentials credentials = new BankAccountCredentials("123-456", "1234");

    @Test
    void encryptUsesKmsAndContext() {
        when(provider.getObject()).thenReturn(client);
        when(client.encrypt(any(EncryptRequest.class))).thenAnswer(invocation -> {
            EncryptRequest request = invocation.getArgument(0);
            assertThat(request.keyId()).isEqualTo("alias/account-credentials");
            assertThat(request.encryptionContext()).containsEntry("userId", "1")
                    .containsEntry("organization", "0020");
            return EncryptResponse.builder().ciphertextBlob(SdkBytes.fromUtf8String("encrypted")).build();
        });

        assertThat(cipher().encrypt(1L, "0020", credentials)).startsWith("kms:v1:");
    }

    @Test
    void serviceFailureIsTemporaryButAccessDeniedIsNot() {
        when(provider.getObject()).thenReturn(client);
        when(client.encrypt(any(EncryptRequest.class)))
                .thenThrow(KmsException.builder().statusCode(503).message("unavailable").build())
                .thenThrow(KmsException.builder().statusCode(403).message("denied").build());

        assertThatThrownBy(() -> cipher().encrypt(1L, "0020", credentials))
                .isInstanceOf(TemporaryKmsUnavailableException.class);
        assertThatThrownBy(() -> cipher().encrypt(1L, "0020", credentials))
                .isInstanceOf(ApplicationException.class);
    }

    private KmsBankAccountCredentialCipher cipher() {
        return new KmsBankAccountCredentialCipher(provider, "alias/account-credentials");
    }
}
