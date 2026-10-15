package com.amateur.howmuchbackend.application.certificate;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.certificate.policy.CertificateRegistrationPolicy;
import com.amateur.howmuchbackend.application.port.in.certificate.command.SubmitCertificateUploadCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class CertificatePolicyTest {
    @Test
    void boundedGlobalCapacityRejectsOneMoreSession() {
        var p = new CertificateRegistrationPolicy();
        p.capacity(199);
        assertThatThrownBy(() -> p.capacity(200)).isInstanceOf(ApplicationException.class);
    }

    @Test
    void rejectsInvalidCalendarDateAndUnpairedCredentials() {
        var p = new CertificateRegistrationPolicy();
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                p.material(
                                        new SubmitCertificateUploadCommand(
                                                id,
                                                "pc",
                                                "upload",
                                                new byte[] {1},
                                                new byte[] {2},
                                                "fixture",
                                                null,
                                                null,
                                                "19900230")))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(
                        () ->
                                p.material(
                                        new SubmitCertificateUploadCommand(
                                                id,
                                                "pc",
                                                "upload",
                                                new byte[] {1},
                                                new byte[] {2},
                                                "fixture",
                                                "123456",
                                                null,
                                                null)))
                .isInstanceOf(ApplicationException.class);
    }
}
