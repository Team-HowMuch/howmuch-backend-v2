package com.amateur.howmuchbackend.application.port.in.certificate.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateConnectionDecisionResult(
        UUID sessionId,
        UUID claimId,
        String organization,
        String status,
        Long connectionId,
        LocalDateTime expiresAt,
        String failureCode,
        String failureMessage) {}
