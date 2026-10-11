package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificateConsent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CertificateConsentStorePort {
    Optional<CertificateConsent> find(UUID sessionId);

    void approve(CertificateConsent consent, LocalDateTime now);

    void bind(CertificateConsent consent, UUID connectionRef, long connectionId, LocalDateTime now);
}
