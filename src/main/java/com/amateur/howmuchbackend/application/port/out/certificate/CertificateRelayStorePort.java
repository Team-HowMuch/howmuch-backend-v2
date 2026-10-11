package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificatePublication;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CertificateRelayStorePort {
    Optional<CertificatePublication> claimPublication(boolean result, LocalDateTime now);

    boolean completePublication(
            CertificatePublication publication, boolean acknowledged, LocalDateTime now);

    void recoverPublications(LocalDateTime now);
}
