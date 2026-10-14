package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.util.UUID;

public record PairCertificateDeviceCommand(UUID pairingId, String qrToken, String organization) {
    @Override
    public String toString() {
        return "PairCertificateDeviceCommand[redacted]";
    }
}
