package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

import java.util.UUID;

public interface PairCertificateDeviceUseCase {
    CertificatePairingResult pair(UUID id, String qrToken);
}
