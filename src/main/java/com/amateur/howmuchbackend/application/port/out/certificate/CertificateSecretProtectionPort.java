package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;

import java.util.UUID;

public interface CertificateSecretProtectionPort {
    CertificateEnvelope sealUpload(UUID session, UUID upload, CertificateMaterial material);

    CertificateMaterial openUpload(UUID session, UUID upload, CertificateEnvelope envelope);

    CertificateEnvelope sealConnection(
            UUID session, UUID connection, CertificateConnectionSecret secret);

    CertificateConnectionSecret openConnection(
            UUID session, UUID connection, CertificateEnvelope envelope);
}
