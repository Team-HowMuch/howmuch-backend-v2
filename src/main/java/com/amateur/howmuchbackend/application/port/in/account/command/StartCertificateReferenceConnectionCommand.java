package com.amateur.howmuchbackend.application.port.in.account.command;

import java.util.UUID;

public record StartCertificateReferenceConnectionCommand(
        long connectionId,
        UUID idempotencyKey,
        String sourceOrganization,
        String organization,
        String birthDate,
        String accountNumber,
        String accountPassword) {
    @Override
    public String toString() {
        return "StartCertificateReferenceConnectionCommand[connectionId="
                + connectionId
                + ", sourceOrganization="
                + sourceOrganization
                + ", organization="
                + organization
                + ", credentials=REDACTED]";
    }
}
