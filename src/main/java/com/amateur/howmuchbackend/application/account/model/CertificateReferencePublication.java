package com.amateur.howmuchbackend.application.account.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateReferencePublication(
        UUID messageId,
        UUID operationId,
        LocalDateTime occurredAt,
        UUID leaseToken,
        long generation) {}
