package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.util.UUID;

public record StartPairedCertificateRegistrationCommand(
        UUID pairingId, String pcCapability, String organization, String remoteAddress) {
    @Override
    public String toString() {
        return "StartPairedCertificateRegistrationCommand[REDACTED]";
    }
}
