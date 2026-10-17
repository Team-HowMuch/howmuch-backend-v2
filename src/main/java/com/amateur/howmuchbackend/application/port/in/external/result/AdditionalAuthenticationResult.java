package com.amateur.howmuchbackend.application.port.in.external.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdditionalAuthenticationResult(
        UUID challengeId,
        String status,
        String method,
        String maskedDestination,
        LocalDateTime expiresAt,
        String failureCode,
        String failureMessage,
        boolean cancelRequested,
        String operationType,
        String organization,
        com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult
                bankConnection,
        UUID pairingId,
        UUID sessionId,
        UUID retryChallengeId) {
    public AdditionalAuthenticationResult(
            UUID id,
            String status,
            String method,
            String destination,
            LocalDateTime expires,
            String code,
            String message,
            boolean cancel) {
        this(
                id,
                status,
                method,
                destination,
                expires,
                code,
                message,
                cancel,
                "CERTIFICATE_REGISTER",
                null,
                null,
                null,
                null,
                null);
    }
}
