package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.util.UUID;

public record SubmitCertificateUploadCommand(
        UUID sessionId,
        String pcCapability,
        String uploadCapability,
        byte[] der,
        byte[] key,
        String password,
        String accountNumber,
        String accountPassword,
        String birthDate) {
    @Override
    public String toString() {
        return "SubmitCertificateUploadCommand[REDACTED]";
    }
}
