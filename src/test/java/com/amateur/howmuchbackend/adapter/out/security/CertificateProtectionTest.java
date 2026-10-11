package com.amateur.howmuchbackend.adapter.out.security;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import software.amazon.awssdk.services.kms.KmsClient;

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
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        return new CertificateSecretProtectionAdapter(
                properties(),
                new StaticListableBeanFactory().getBeanProvider(KmsClient.class),
                env);
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

    @Test
    void productionRejectsLocalWrapping() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(
                        () ->
                                new CertificateSecretProtectionAdapter(
                                        properties(),
                                        new StaticListableBeanFactory()
                                                .getBeanProvider(KmsClient.class),
                                        env))
                .isInstanceOf(IllegalArgumentException.class);
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
}
