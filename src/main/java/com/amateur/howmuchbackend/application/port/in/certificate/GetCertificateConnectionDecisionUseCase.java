package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateConnectionDecisionResult;

import java.util.UUID;

public interface GetCertificateConnectionDecisionUseCase {
    CertificateConnectionDecisionResult getDecision(UUID sessionId, UUID claimId);
}
