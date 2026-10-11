package com.amateur.howmuchbackend.adapter.out.security;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.CERTIFICATE_STORAGE_UNAVAILABLE;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateSecretProtectionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public final class CertificateSecretProtectionAdapter implements CertificateSecretProtectionPort {
    private final CertificateProperties properties;
    private final ObjectProvider<KmsClient> kms;
    private final byte[] localKey;
    private final SecureRandom random = new SecureRandom();

    public CertificateSecretProtectionAdapter(
            CertificateProperties p, ObjectProvider<KmsClient> kms, Environment env) {
        this.properties = p;
        this.kms = kms;
        if (p.kmsKeyId().isBlank()) {
            if (!env.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "test")))
                throw new IllegalArgumentException(
                        "Certificate storage requires KMS outside local/test");
            localKey = Base64.getDecoder().decode(p.localWrappingKey());
            if (localKey.length != 32)
                throw new IllegalArgumentException(
                        "Certificate local wrapping key must be 256 bits");
        } else localKey = null;
    }

    public CertificateEnvelope sealUpload(UUID s, UUID u, CertificateMaterial m) {
        return seal(context("upload", s, u), encodeUpload(m));
    }

    public CertificateMaterial openUpload(UUID s, UUID u, CertificateEnvelope e) {
        byte[] b = open(context("upload", s, u), e);
        try (var in = new DataInputStream(new ByteArrayInputStream(b))) {
            byte[] der = bytes(in), key = bytes(in);
            try {
                return new CertificateMaterial(
                        der, key, in.readUTF(), nullable(in), nullable(in), nullable(in));
            } finally {
                Arrays.fill(der, (byte) 0);
                Arrays.fill(key, (byte) 0);
            }
        } catch (IOException ex) {
            throw unavailable();
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    public CertificateEnvelope sealConnection(UUID s, UUID c, CertificateConnectionSecret secret) {
        try {
            var b = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(b)) {
                out.writeUTF(secret.connectedId());
                text(out, secret.accountNumber());
                text(out, secret.accountPassword());
                text(out, secret.birthDate());
            }
            return seal(context("connection", s, c), b.toByteArray());
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    public CertificateConnectionSecret openConnection(UUID s, UUID c, CertificateEnvelope e) {
        byte[] b = open(context("connection", s, c), e);
        try (var in = new DataInputStream(new ByteArrayInputStream(b))) {
            return new CertificateConnectionSecret(
                    in.readUTF(), nullable(in), nullable(in), nullable(in));
        } catch (IOException ex) {
            throw unavailable();
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    private byte[] encodeUpload(CertificateMaterial m) {
        try {
            var b = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(b)) {
                bytes(out, m.der());
                bytes(out, m.key());
                out.writeUTF(m.password());
                text(out, m.accountNumber());
                text(out, m.accountPassword());
                text(out, m.birthDate());
            }
            return b.toByteArray();
        } catch (IOException ex) {
            throw unavailable();
        }
    }

    private CertificateEnvelope seal(String context, byte[] plaintext) {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        try {
            byte[] wrapped;
            String keyId;
            if (localKey != null) {
                wrapped = crypt(Cipher.ENCRYPT_MODE, localKey, context, dek);
                keyId = "local:v1";
            } else {
                var r =
                        kms.getObject()
                                .encrypt(
                                        EncryptRequest.builder()
                                                .keyId(properties.kmsKeyId())
                                                .encryptionContext(Map.of("purpose", context))
                                                .plaintext(SdkBytes.fromByteArray(dek))
                                                .build());
                wrapped = r.ciphertextBlob().asByteArray();
                keyId = r.keyId();
            }
            return new CertificateEnvelope(
                    crypt(Cipher.ENCRYPT_MODE, dek, context, plaintext), wrapped, keyId);
        } catch (RuntimeException ex) {
            throw unavailable();
        } finally {
            Arrays.fill(dek, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] open(String context, CertificateEnvelope e) {
        byte[] dek = null;
        try {
            if ("local:v1".equals(e.keyId())) {
                if (localKey == null) throw unavailable();
                dek = crypt(Cipher.DECRYPT_MODE, localKey, context, e.wrappedKey());
            } else {
                if (localKey != null) throw unavailable();
                dek =
                        kms.getObject()
                                .decrypt(
                                        DecryptRequest.builder()
                                                .keyId(properties.kmsKeyId())
                                                .encryptionContext(Map.of("purpose", context))
                                                .ciphertextBlob(
                                                        SdkBytes.fromByteArray(e.wrappedKey()))
                                                .build())
                                .plaintext()
                                .asByteArray();
            }
            if (dek.length != 32) throw unavailable();
            return crypt(Cipher.DECRYPT_MODE, dek, context, e.payload());
        } catch (RuntimeException ex) {
            throw unavailable();
        } finally {
            if (dek != null) Arrays.fill(dek, (byte) 0);
        }
    }

    private byte[] crypt(int mode, byte[] key, String context, byte[] source) {
        try {
            byte[] nonce = new byte[12], input;
            if (mode == Cipher.ENCRYPT_MODE) {
                random.nextBytes(nonce);
                input = source;
            } else {
                if (source.length < 29) throw unavailable();
                System.arraycopy(source, 0, nonce, 0, 12);
                input = Arrays.copyOfRange(source, 12, source.length);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] result = cipher.doFinal(input);
            return mode == Cipher.ENCRYPT_MODE
                    ? ByteBuffer.allocate(12 + result.length).put(nonce).put(result).array()
                    : result;
        } catch (GeneralSecurityException ex) {
            throw unavailable();
        }
    }

    private String context(String purpose, UUID s, UUID r) {
        return "certificate-" + purpose + ":v1:" + s + ":" + r;
    }

    private void text(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) out.writeUTF(text);
    }

    private String nullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private void bytes(DataOutputStream out, byte[] b) throws IOException {
        try {
            out.writeInt(b.length);
            out.write(b);
        } finally {
            Arrays.fill(b, (byte) 0);
        }
    }

    private byte[] bytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 1 || n > 131072) throw new IOException();
        byte[] b = in.readNBytes(n);
        if (b.length != n) throw new IOException();
        return b;
    }

    private ApplicationException unavailable() {
        return ApplicationException.of(CERTIFICATE_STORAGE_UNAVAILABLE);
    }
}
