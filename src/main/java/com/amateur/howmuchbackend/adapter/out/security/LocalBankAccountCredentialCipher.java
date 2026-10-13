package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.port.out.external.LocalAccountCredentialCipherPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Base64;

@Component
public class LocalBankAccountCredentialCipher implements LocalAccountCredentialCipherPort {
    private static final String VERSION = "local:v1:";
    private static final int NONCE_LENGTH = 12;
    private final String encodedKey;

    public LocalBankAccountCredentialCipher(@Value("${ACCOUNT_CREDENTIAL_LOCAL_KEY:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    @Override
    public String encrypt(Long userId, String organization, BankAccountCredentials credentials) {
        try {
            byte[] plaintext = BankAccountCredentialCodec.encode(credentials);
            try {
                return VERSION + Base64.getEncoder().encodeToString(
                        AesGcmCipher.encrypt(key(), context(userId, organization), plaintext));
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_CONFIGURATION_INVALID);
        }
    }

    @Override
    public BankAccountCredentials decrypt(Long userId, String organization, String ciphertext) {
        try {
            if (ciphertext == null || !ciphertext.startsWith(VERSION)) {
                throw new IllegalArgumentException();
            }
            byte[] value = Base64.getDecoder().decode(ciphertext.substring(VERSION.length()));
            if (value.length <= NONCE_LENGTH + 16) {
                throw new IllegalArgumentException();
            }
            byte[] plaintext = AesGcmCipher.decrypt(key(), context(userId, organization), value);
            try {
                return BankAccountCredentialCodec.decode(plaintext);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID);
        }
    }

    private String context(Long userId, String organization) {
        return "bank-account:" + userId + ":" + organization;
    }

    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(encodedKey);
            if (key.length != 32) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_CONFIGURATION_INVALID);
        }
    }
}
