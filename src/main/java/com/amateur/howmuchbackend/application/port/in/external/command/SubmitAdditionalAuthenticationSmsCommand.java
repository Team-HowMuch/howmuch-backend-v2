package com.amateur.howmuchbackend.application.port.in.external.command;

import java.util.UUID;

public record SubmitAdditionalAuthenticationSmsCommand(
        UUID challengeId, UUID submissionId, String code) {
    @Override
    public String toString() {
        return "SubmitAdditionalAuthenticationSmsCommand[redacted]";
    }
}
