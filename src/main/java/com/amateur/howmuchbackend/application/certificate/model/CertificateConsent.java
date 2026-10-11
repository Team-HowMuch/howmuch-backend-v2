package com.amateur.howmuchbackend.application.certificate.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateConsent(
        UUID id,
        UUID sessionId,
        UUID claimId,
        long userId,
        long generation,
        LocalDateTime approvedAt,
        Long connectionId) {}
