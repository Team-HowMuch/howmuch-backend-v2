package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateQrResult;

import java.util.UUID;

public interface IssueCertificateQrUseCase {
    CertificateQrResult issue(UUID sessionId, String pcCapability);
}
