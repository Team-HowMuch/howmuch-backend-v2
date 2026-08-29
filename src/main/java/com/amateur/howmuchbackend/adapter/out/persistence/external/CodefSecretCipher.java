package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.infra.codef.config.CodefProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CodefSecretCipher {
    private static final String LEGACY_VERSION = "v1:";
    private static final String SETTLEMENT_SNAPSHOT_VERSION = "v2:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CodefProperties properties;

    public void validateConfiguration() {
        key();
    }

    public String encryptConnectedId(Long userId, String connectedId) {
        return encrypt(LEGACY_VERSION, "connected:" + userId, connectedId);
    }

    public String decryptConnectedId(Long userId, String ciphertext) {
        return decrypt(LEGACY_VERSION, "connected:" + userId, ciphertext);
    }

    public String encryptAccountKey(Long userId, Long connectionId, String accountKey) {
        return encrypt(
                LEGACY_VERSION,
                "account:" + userId + ":" + connectionId,
                accountKey
        );
    }

    public String decryptAccountKey(Long userId, Long connectionId, String ciphertext) {
        return decrypt(
                LEGACY_VERSION,
                "account:" + userId + ":" + connectionId,
                ciphertext
        );
    }

    public String encryptSettlementAccountNumber(
            UUID settlementId,
            String accountNumber
    ) {
        return encrypt(
                SETTLEMENT_SNAPSHOT_VERSION,
                "settlement-account:" + settlementId,
                accountNumber
        );
    }

    public String decryptSettlementAccountNumber(
            UUID settlementId,
            String ciphertext
    ) {
        return decrypt(
                SETTLEMENT_SNAPSHOT_VERSION,
                "settlement-account:" + settlementId,
                ciphertext
        );
    }

    public boolean isSettlementAccountNumberCiphertext(String ciphertext) {
        return ciphertext != null
                && ciphertext.startsWith(SETTLEMENT_SNAPSHOT_VERSION);
    }

    public String encryptSettlementLinkToken(UUID settlementId, String rawToken) {
        return encrypt(
                SETTLEMENT_SNAPSHOT_VERSION,
                "settlement-link:" + settlementId,
                rawToken
        );
    }

    public String decryptSettlementLinkToken(UUID settlementId, String ciphertext) {
        return decrypt(
                SETTLEMENT_SNAPSHOT_VERSION,
                "settlement-link:" + settlementId,
                ciphertext
        );
    }

    private String encrypt(String version, String aad, String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, aad).doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8)
            );
            return version + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array()
            );
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID, exception);
        }
    }

    private String decrypt(String version, String aad, String ciphertext) {
        try {
            if (ciphertext == null || !ciphertext.startsWith(version)) {
                throw new IllegalArgumentException("Unsupported CODEF ciphertext.");
            }
            byte[] value = Base64.getDecoder().decode(ciphertext.substring(version.length()));
            if (value.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("Invalid CODEF ciphertext.");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(value, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(value, NONCE_BYTES, value.length);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID, exception);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(properties.connectedIdEncryptionKey());
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("Invalid AES key size.");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID, exception);
        }
    }
}
