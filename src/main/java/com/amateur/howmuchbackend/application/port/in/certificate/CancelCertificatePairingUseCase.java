package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

import java.util.UUID;

public interface CancelCertificatePairingUseCase {
    void cancelPairing(UUID id, String pc);

    void cancelMobilePairing(UUID id);
}
