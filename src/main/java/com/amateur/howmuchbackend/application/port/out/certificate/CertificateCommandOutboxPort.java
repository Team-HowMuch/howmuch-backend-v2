package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.domain.certificate.CertificateSession;

import java.time.LocalDateTime;

public interface CertificateCommandOutboxPort {
    void enqueue(CertificateSession session, String type, LocalDateTime now);
}
