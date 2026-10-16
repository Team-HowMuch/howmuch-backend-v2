package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.application.account.model.CertificateReferenceSecret;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.domain.account.CertificateReferenceOperation;

public interface CertificateReferenceProtectionPort {
    String requestHash(long userId, StartCertificateReferenceConnectionCommand command);

    String connectionHash(long userId, String connectedId);

    CertificateEnvelope sealReference(
            CertificateReferenceOperation operation, CertificateReferenceSecret secret);

    CertificateReferenceSecret openReference(
            CertificateReferenceOperation operation, CertificateEnvelope envelope);
}
