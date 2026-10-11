package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;

import java.time.LocalDateTime;
import java.util.UUID;

public interface CertificateUploadStorePort {
    void issue(
            UUID id,
            UUID sessionId,
            String capabilityHash,
            LocalDateTime now,
            LocalDateTime expires);

    boolean accepts(UUID id, String capabilityHash, LocalDateTime now);

    boolean store(UUID id, String capabilityHash, CertificateEnvelope envelope, LocalDateTime now);

    CertificateEnvelope load(UUID id, LocalDateTime now);

    void clear(UUID id, String status, LocalDateTime now);
}
