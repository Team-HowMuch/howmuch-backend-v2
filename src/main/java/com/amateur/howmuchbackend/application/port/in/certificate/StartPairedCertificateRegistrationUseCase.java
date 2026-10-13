package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

public interface StartPairedCertificateRegistrationUseCase {
    CertificateRegistrationResult createRegistration(
            com.amateur.howmuchbackend.application.port.in.certificate.command
                            .StartPairedCertificateRegistrationCommand
                    command);
}
