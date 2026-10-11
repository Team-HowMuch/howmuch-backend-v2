package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.CERTIFICATE_INPUT_INVALID;

import com.amateur.howmuchbackend.application.port.in.certificate.command.SubmitCertificateUploadCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import jakarta.validation.constraints.*;

import java.util.*;

public record SubmitCertificateUploadWebRequest(
        @NotBlank @Size(max = 174764) String derFile,
        @NotBlank @Size(max = 174764) String keyFile,
        @NotBlank @Size(max = 256) String password,
        @Size(max = 30) String accountNumber,
        @Size(max = 8) String accountPassword,
        @Size(max = 8) String birthDate) {
    public SubmitCertificateUploadCommand toCommand(UUID id, String pc, String upload) {
        try {
            return new SubmitCertificateUploadCommand(
                    id,
                    pc,
                    upload,
                    Base64.getDecoder().decode(derFile),
                    Base64.getDecoder().decode(keyFile),
                    password,
                    accountNumber,
                    accountPassword,
                    birthDate);
        } catch (IllegalArgumentException e) {
            throw ApplicationException.of(CERTIFICATE_INPUT_INVALID);
        }
    }

    @Override
    public String toString() {
        return "SubmitCertificateUploadWebRequest[REDACTED]";
    }
}
