package com.amateur.howmuchbackend.adapter.in.web.external.dto.response;

import com.amateur.howmuchbackend.application.port.in.external.result.AdditionalAuthenticationResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdditionalAuthenticationWebResponse(
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
        com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse
                bankConnection,
        UUID pairingId,
        UUID sessionId,
        UUID retryChallengeId) {

    public AdditionalAuthenticationWebResponse(
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

    public static AdditionalAuthenticationWebResponse from(AdditionalAuthenticationResult r) {
        return r == null
                ? null
                : new AdditionalAuthenticationWebResponse(
                        r.challengeId(),
                        r.status(),
                        r.method(),
                        r.maskedDestination(),
                        r.expiresAt(),
                        r.failureCode(),
                        r.failureMessage(),
                        r.cancelRequested(),
                        r.operationType(),
                        r.organization(),
                        r.bankConnection() == null
                                ? null
                                : com.amateur.howmuchbackend.adapter.in.web.account.dto.response
                                        .BankConnectionWebResponse.from(r.bankConnection()),
                        r.pairingId(),
                        r.sessionId(),
                        r.retryChallengeId());
    }
}
