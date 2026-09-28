package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBankAccountCredentialCipherTest {
    private final LocalBankAccountCredentialCipher cipher = new LocalBankAccountCredentialCipher(
            Base64.getEncoder().encodeToString(new byte[32])
    );

    @Test
    void roundTripAndContextBinding() {
        var credentials = new BankAccountCredentials("123-456", "1234");
        String ciphertext = cipher.encrypt(1L, "0020", credentials);

        assertThat(ciphertext).startsWith("local:v1:").doesNotContain(credentials.number(), credentials.password());
        assertThat(cipher.decrypt(1L, "0020", ciphertext)).isEqualTo(credentials);
        assertThatThrownBy(() -> cipher.decrypt(2L, "0020", ciphertext))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID
                        ));
    }
}
