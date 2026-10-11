package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.CertificatePublication;

public interface CertificateEventPublisherPort {
    void publish(CertificatePublication publication);
}
