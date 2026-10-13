package com.amateur.howmuchbackend.adapter.out.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Shared nonce-prefixed AES-GCM format; callers own key selection, AAD and error mapping. */
final class AesGcmCipher {
    private static final int NONCE_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmCipher() {}

    static byte[] encrypt(byte[] key, String context, byte[] plaintext)
            throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, context).doFinal(plaintext);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    static byte[] decrypt(byte[] key, String context, byte[] ciphertext)
            throws GeneralSecurityException {
        if (ciphertext == null || ciphertext.length < NONCE_LENGTH + 16) {
            throw new IllegalArgumentException("Invalid AES-GCM ciphertext");
        }
        byte[] nonce = Arrays.copyOfRange(ciphertext, 0, NONCE_LENGTH);
        return cipher(Cipher.DECRYPT_MODE, key, nonce, context)
                .doFinal(ciphertext, NONCE_LENGTH, ciphertext.length - NONCE_LENGTH);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, String context)
            throws GeneralSecurityException {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-GCM key must be 256 bits");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
