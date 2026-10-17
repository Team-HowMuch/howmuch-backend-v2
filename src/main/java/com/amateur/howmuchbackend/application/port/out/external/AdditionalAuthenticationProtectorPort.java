package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationChallenge;

import java.util.UUID;

public interface AdditionalAuthenticationProtectorPort {
    CertificateEnvelope sealAuthentication(
            UUID id, long user, UUID operation, long generation, byte[] plaintext);

    byte[] openAuthentication(AdditionalAuthenticationChallenge challenge);

    default CertificateEnvelope sealResult(AdditionalAuthenticationChallenge c, byte[] plaintext) {
        throw new UnsupportedOperationException();
    }

    default byte[] openResult(AdditionalAuthenticationChallenge c, CertificateEnvelope result) {
        throw new UnsupportedOperationException();
    }
}
