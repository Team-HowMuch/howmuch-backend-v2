package com.amateur.howmuchbackend.adapter.in.web.external.dto.request;

import com.amateur.howmuchbackend.application.port.in.external.command.SubmitAdditionalAuthenticationSmsCommand;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record SubmitAdditionalAuthenticationSmsWebRequest(
        @NotNull UUID submissionId, @NotBlank @Pattern(regexp = "[0-9]{4,8}") String code) {
    public SubmitAdditionalAuthenticationSmsCommand toCommand(UUID id) {
        return new SubmitAdditionalAuthenticationSmsCommand(id, submissionId, code);
    }

    @Override
    public String toString() {
        return "SubmitAdditionalAuthenticationSmsWebRequest[redacted]";
    }
}
