package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

public interface CreateCertificatePairingUseCase {
    CertificatePairingResult createPairing(String remoteAddress);
}
