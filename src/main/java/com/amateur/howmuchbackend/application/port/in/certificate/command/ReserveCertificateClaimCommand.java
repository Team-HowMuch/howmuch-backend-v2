package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.util.UUID;

public record ReserveCertificateClaimCommand(UUID sessionId, String qrToken, UUID idempotencyKey) {
    @Override
    public String toString() {
        return "ReserveCertificateClaimCommand[redacted]";
    }
}
