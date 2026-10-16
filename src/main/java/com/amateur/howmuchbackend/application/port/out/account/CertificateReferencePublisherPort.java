package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.application.account.model.CertificateReferencePublication;

public interface CertificateReferencePublisherPort {
    void publish(CertificateReferencePublication publication);
}
