package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificateClaim;

import java.time.LocalDateTime;
import java.util.*;

public interface CertificateClaimStorePort {
    Optional<CertificateClaim> current(UUID sessionId);

    Optional<CertificateClaim> findById(UUID sessionId, UUID claimId);

    Optional<CertificateClaim> find(UUID sessionId, long userId, UUID idempotencyKey);

    void issueQr(
            UUID sessionId, String hash, long generation, LocalDateTime expiry, LocalDateTime now);

    void reserve(CertificateClaim claim, String codeHash, LocalDateTime now);

    void close(CertificateClaim claim, String status, LocalDateTime now);
}
