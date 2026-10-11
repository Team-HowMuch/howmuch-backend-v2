package com.amateur.howmuchbackend.application.port.in.certificate.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateQrResult(
        UUID sessionId, String qrToken, long generation, LocalDateTime expiresAt) {
    @Override
    public String toString() {
        return "CertificateQrResult[redacted]";
    }
}
