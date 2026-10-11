package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.domain.certificate.CertificateSession;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CertificateSessionStorePort {
    long countActive(java.time.LocalDateTime now);

    void create(
            UUID id,
            String organization,
            String pcHash,
            UUID uploadRef,
            LocalDateTime now,
            LocalDateTime expires);

    /** Provider-specific stored errors are normalized to common codes at this boundary. */
    Optional<CertificateSession> find(UUID id, boolean lock);

    void submit(UUID id, LocalDateTime now);

    void cancel(UUID id, LocalDateTime now);
}
