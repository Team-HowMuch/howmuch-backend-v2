package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateQrResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateQrWebResponse(
        UUID sessionId, String qrToken, long generation, LocalDateTime expiresAt) {
    public static CertificateQrWebResponse from(CertificateQrResult r) {
        return new CertificateQrWebResponse(
                r.sessionId(), r.qrToken(), r.generation(), r.expiresAt());
    }

    @Override
    public String toString() {
        return "CertificateQrWebResponse[redacted]";
    }
}
