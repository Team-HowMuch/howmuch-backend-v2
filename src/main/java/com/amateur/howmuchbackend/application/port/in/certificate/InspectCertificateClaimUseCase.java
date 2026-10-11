package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateClaimResult;

import java.util.UUID;

public interface InspectCertificateClaimUseCase {
    CertificateClaimResult inspect(UUID sessionId, String pcCapability);

    void reject(UUID sessionId, String pcCapability, UUID claimId);
}
