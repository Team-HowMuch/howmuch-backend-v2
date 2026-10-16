package com.amateur.howmuchbackend.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.util.*;

class CertificateProtectionTest {
    static CertificateProperties properties() {
        return new CertificateProperties(
                true,
                "",
                Base64.getEncoder().encodeToString(new byte[32]),
                Base64.getEncoder().encodeToString(new byte[32]),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    static CertificateSecretProtectionAdapter cipher() {
        return new CertificateSecretProtectionAdapter(
                properties(),
                new StaticListableBeanFactory().getBeanProvider(KmsClient.class));
    }

    @Test
    void readsExistingLocalEnvelopeWithoutReencrypting() {
        // Synthetic fixed vector from the original envelope format, generated with Node crypto.
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reference = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var envelope =
                new CertificateEnvelope(
                        Base64.getDecoder()
                                .decode("AgICAgICAgICAgICB9GvIDIjtI+2zLzI0M0h2+0d4mvNZJf4IA8nHw=="),
                        Base64.getDecoder()
                                .decode(
                                        "AwMDAwMDAwMDAwMDmTI1JcsHPZEmNhQXrCbHGL6rnw8/BNB+rKO9/t713wOucqSQTUcp3Iyqzlt3/M9s"),
                        "local:v1");
        assertThat(cipher().openConnection(session, reference, envelope))
                .isEqualTo(new CertificateConnectionSecret("fixture", null, null, null));
    }

    @Test
    void uploadRoundTripUsesRandomizedCiphertextAndBoundContext() {
        var cipher = cipher();
        UUID s = UUID.randomUUID(), u = UUID.randomUUID();
        try (var material =
                new CertificateMaterial(
                        new byte[] {1, 2},
                        new byte[] {3, 4},
                        "password-fixture",
                        "123456",
                        "1234",
                        "19900101")) {
            var sealed = cipher.sealUpload(s, u, material);
            var second = cipher.sealUpload(s, u, material);
            assertThat(sealed.payload()).isNotEqualTo(second.payload());
            try (var open = cipher.openUpload(s, u, sealed)) {
                assertThat(open.der()).containsExactly(1, 2);
                assertThat(open.key()).containsExactly(3, 4);
                assertThat(open.password()).isEqualTo("password-fixture");
            }
            assertThatThrownBy(() -> cipher.openUpload(UUID.randomUUID(), u, sealed))
                    .isInstanceOf(ApplicationException.class);
            byte[] changed = sealed.payload();
            changed[changed.length - 1] ^= 1;
            assertThatThrownBy(
                            () ->
                                    cipher.openUpload(
                                            s,
                                            u,
                                            new CertificateEnvelope(
                                                    changed, sealed.wrappedKey(), sealed.keyId())))
                    .isInstanceOf(ApplicationException.class);
        }
    }

    @Test
    void connectionSecretCannotBeOpenedAsAnUpload() {
        var cipher = cipher();
        UUID s = UUID.randomUUID(), c = UUID.randomUUID();
        var sealed =
                cipher.sealConnection(
                        s,
                        c,
                        new CertificateConnectionSecret(
                                "connected-fixture", "123456", "1234", null));
        assertThat(cipher.openConnection(s, c, sealed).connectedId())
                .isEqualTo("connected-fixture");
        assertThatThrownBy(() -> cipher.openUpload(s, c, sealed))
                .isInstanceOf(ApplicationException.class);
    }

    private CertificateProperties keys(String kmsId, String local) {
        return new CertificateProperties(true, kmsId, local, properties().confirmationKey(),
                List.of(), null, null, null, null, null, null);
    }

    @Test
    void requiresAnExplicitValidKeyWithoutProfileRestrictions() {
        var provider = new StaticListableBeanFactory().getBeanProvider(KmsClient.class);
        for (String invalid :
                List.of("", "not-base64", Base64.getEncoder().encodeToString(new byte[16]))) {
            assertThatThrownBy(
                            () ->
                                    new CertificateSecretProtectionAdapter(
                                            keys("", invalid), provider))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new CertificateSecretProtectionAdapter(properties(), provider))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                new CertificateSecretProtectionAdapter(
                                        keys("alias/certificate", ""), provider))
                .doesNotThrowAnyException();
    }

    @Test
    void kmsModeReadsExistingLocalEnvelopesAndUsesStoredKmsKeyId() {
        UUID session = UUID.randomUUID(), reference = UUID.randomUUID();
        var secret = new CertificateConnectionSecret("connected-fixture", null, null, null);
        var localEnvelope = cipher().sealConnection(session, reference, secret);
        var client = mock(KmsClient.class);
        var factory = new StaticListableBeanFactory(Map.of("kms", client));
        var kmsCipher =
                new CertificateSecretProtectionAdapter(
                        keys("alias/new", properties().localWrappingKey()),
                        factory.getBeanProvider(KmsClient.class));
        assertThat(kmsCipher.openConnection(session, reference, localEnvelope)).isEqualTo(secret);
        verifyNoInteractions(client);
        when(client.encrypt(any(EncryptRequest.class))).thenAnswer(call -> {
            EncryptRequest request = call.getArgument(0);
            return EncryptResponse.builder().keyId("arn:fixture:old-key")
                    .ciphertextBlob(request.plaintext()).build();
        });
        when(client.decrypt(any(DecryptRequest.class))).thenAnswer(call -> {
            DecryptRequest request = call.getArgument(0);
            assertThat(request.keyId()).isEqualTo("arn:fixture:old-key");
            assertThat(request.encryptionContext()).containsEntry("purpose",
                    "certificate-connection:v1:" + session + ":" + reference);
            return DecryptResponse.builder().plaintext(request.ciphertextBlob()).build();
        });
        var envelope = kmsCipher.sealConnection(session, reference, secret);
        assertThat(envelope.keyId()).isEqualTo("arn:fixture:old-key");
        assertThat(kmsCipher.openConnection(session, reference, envelope)).isEqualTo(secret);
        assertThatThrownBy(() -> cipher().openConnection(session, reference, envelope))
                .isInstanceOf(ApplicationException.class);
        when(client.encrypt(any(EncryptRequest.class)))
                .thenThrow(KmsException.builder().message("denied").build());
        assertThatThrownBy(() -> kmsCipher.sealConnection(session, reference, secret))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void wrongLocalKeyAndTamperedWrappedKeyAreRejected() {
        UUID session = UUID.randomUUID(), reference = UUID.randomUUID();
        var envelope = cipher().sealConnection(session, reference,
                new CertificateConnectionSecret("fixture", null, null, null));
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        var other = new CertificateSecretProtectionAdapter(
                keys("", Base64.getEncoder().encodeToString(otherKey)),
                new StaticListableBeanFactory().getBeanProvider(KmsClient.class));
        assertThatThrownBy(() -> other.openConnection(session, reference, envelope))
                .isInstanceOf(ApplicationException.class);
        byte[] wrapped = envelope.wrappedKey();
        wrapped[wrapped.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher().openConnection(session, reference,
                new CertificateEnvelope(envelope.payload(), wrapped, envelope.keyId())))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void capabilitiesAreIndependentAndCodeIsStable() {
        var caps = new CertificateCapabilities(properties());
        String a = caps.mint(), b = caps.mint();
        assertThat(a).hasSize(43).isNotEqualTo(b);
        assertThat(caps.matches(a, caps.hash(a))).isTrue();
        assertThat(caps.matches(b, caps.hash(a))).isFalse();
        UUID id = UUID.randomUUID();
        assertThat(caps.confirmationCode(id))
                .matches("[0-9]{6}")
                .isEqualTo(caps.confirmationCode(id));
    }

    @Test
    void referenceEnvelopeBindsOperationOwnerConnectionAndBothInstitutions() {
        var cipher = cipher();
        var id = UUID.randomUUID();
        var op = referenceOperation(id, 42, 15, "0020", "0088");
        var secret =
                new com.amateur.howmuchbackend.application.account.model.CertificateReferenceSecret(
                        "fixture-connected", "19900101", "123456", " password ");
        var envelope = cipher.sealReference(op, secret);
        assertThat(cipher.openReference(op, envelope)).isEqualTo(secret);
        assertThat(envelope.payload()).isNotEqualTo(cipher.sealReference(op, secret).payload());
        for (var other :
                List.of(
                        referenceOperation(UUID.randomUUID(), 42, 15, "0020", "0088"),
                        referenceOperation(id, 43, 15, "0020", "0088"),
                        referenceOperation(id, 42, 16, "0020", "0088"),
                        referenceOperation(id, 42, 15, "0004", "0088"),
                        referenceOperation(id, 42, 15, "0020", "0004")))
            assertThatThrownBy(() -> cipher.openReference(other, envelope))
                    .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> cipher.openConnection(id, id, envelope))
                .isInstanceOf(ApplicationException.class);
        assertThat(secret.toString())
                .doesNotContain("fixture-connected", "19900101", "123456", "password");
    }

    @Test
    void referenceRequestHashUsesFramedFieldsAndOwnerAndIgnoresIdempotencyKey() {
        var cipher = cipher();
        var a =
                new com.amateur.howmuchbackend.application.port.in.account.command
                        .StartCertificateReferenceConnectionCommand(
                        15, UUID.randomUUID(), "0020", "0088", null, "123456", " password ");
        var b =
                new com.amateur.howmuchbackend.application.port.in.account.command
                        .StartCertificateReferenceConnectionCommand(
                        15, UUID.randomUUID(), "0020", "0088", null, "123456", " password ");
        assertThat(cipher.requestHash(42, a))
                .matches("[0-9a-f]{64}")
                .isEqualTo(cipher.requestHash(42, b))
                .isNotEqualTo(cipher.requestHash(43, b))
                .isNotEqualTo(cipher.connectionHash(42, "123456"));
        var changed =
                new com.amateur.howmuchbackend.application.port.in.account.command
                        .StartCertificateReferenceConnectionCommand(
                        15, a.idempotencyKey(), "0020", "0088", null, "123456", "password");
        assertThat(cipher.requestHash(42, a)).isNotEqualTo(cipher.requestHash(42, changed));
    }

    private com.amateur.howmuchbackend.domain.account.CertificateReferenceOperation
            referenceOperation(UUID id, long owner, long connection, String source, String target) {
        var now = java.time.LocalDateTime.of(2026, 10, 16, 0, 0);
        return new com.amateur.howmuchbackend.domain.account.CertificateReferenceOperation(
                id,
                owner,
                connection,
                source,
                target,
                UUID.randomUUID(),
                "a".repeat(64),
                "b".repeat(64),
                com.amateur.howmuchbackend.domain.account.CertificateReferenceStatus.QUEUED,
                false,
                null,
                now,
                now.plusMinutes(10),
                null,
                null);
    }
}
