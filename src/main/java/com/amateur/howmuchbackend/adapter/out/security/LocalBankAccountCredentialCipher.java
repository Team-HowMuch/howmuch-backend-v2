package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.port.out.external.LocalAccountCredentialCipherPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class LocalBankAccountCredentialCipher implements LocalAccountCredentialCipherPort {
    private static final String VERSION = "local:v1:";
    private static final int NONCE_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String encodedKey;

    public LocalBankAccountCredentialCipher(@Value("${ACCOUNT_CREDENTIAL_LOCAL_KEY:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    @Override
    public String encrypt(Long userId, String organization, BankAccountCredentials credentials) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, userId, organization)
                    .doFinal(BankAccountCredentialCodec.encode(credentials));
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array()
            );
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
            byte[] nonce = java.util.Arrays.copyOfRange(value, 0, NONCE_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(value, NONCE_LENGTH, value.length);
            return BankAccountCredentialCodec.decode(
                    cipher(Cipher.DECRYPT_MODE, nonce, userId, organization).doFinal(encrypted)
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, Long userId, String organization)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("bank-account:" + userId + ":" + organization).getBytes(StandardCharsets.UTF_8));
        return cipher;
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
