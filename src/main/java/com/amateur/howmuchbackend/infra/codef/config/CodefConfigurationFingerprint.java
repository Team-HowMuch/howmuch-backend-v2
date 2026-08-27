package com.amateur.howmuchbackend.infra.codef.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class CodefConfigurationFingerprint {
    private final CodefProperties properties;

    public String current() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, properties.baseUrl());
            update(digest, properties.oauthUrl());
            update(digest, properties.clientId());
            update(digest, properties.clientSecret());
            update(digest, properties.publicKey());
            update(digest, properties.connectedIdEncryptionKey());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
