package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import com.amateur.howmuchbackend.application.port.in.certificate.command.CreateCertificateRegistrationCommand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCertificateRegistrationWebRequest(
        @NotBlank @Pattern(regexp = "[0-9]{4}") String organization) {
    public CreateCertificateRegistrationCommand toCommand(String remoteAddress) {
        return new CreateCertificateRegistrationCommand(organization, remoteAddress);
    }
}
