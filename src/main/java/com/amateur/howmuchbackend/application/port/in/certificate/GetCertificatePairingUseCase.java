package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

import java.util.UUID;

public interface GetCertificatePairingUseCase {
    CertificatePairingResult getPcPairing(UUID id, String pc);

    CertificatePairingResult getMobilePairing(UUID id);
}
