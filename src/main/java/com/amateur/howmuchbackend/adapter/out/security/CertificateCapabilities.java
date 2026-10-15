package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.port.out.certificate.CertificateCapabilityPort;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public final class CertificateCapabilities implements CertificateCapabilityPort {
    private final SecureRandom random = new SecureRandom();
    private final byte[] confirmationKey;

    public CertificateCapabilities(CertificateProperties p) {
        confirmationKey = Base64.getDecoder().decode(p.confirmationKey());
        if (confirmationKey.length != 32)
            throw new IllegalArgumentException("Certificate confirmation key must be 256 bits");
    }

    public String mint() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public boolean matches(String raw, String expected) {
        return raw != null
                && raw.matches("[A-Za-z0-9_-]{43}")
                && expected != null
                && MessageDigest.isEqual(
                        hash(raw).getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII));
    }

    public String confirmationCode(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(confirmationKey, "HmacSHA256"));
            return String.format(
                    Locale.ROOT,
                    "%06d",
                    Math.floorMod(
                            ByteBuffer.wrap(
                                            mac.doFinal(
                                                    id.toString()
                                                            .getBytes(StandardCharsets.US_ASCII)))
                                    .getInt(),
                            1000000));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }
}
