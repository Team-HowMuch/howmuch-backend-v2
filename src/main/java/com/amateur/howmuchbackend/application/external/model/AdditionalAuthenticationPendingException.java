package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import java.util.UUID;

public class AdditionalAuthenticationPendingException extends ApplicationException {
    private final UUID challengeId;

    public AdditionalAuthenticationPendingException(UUID id) {
        super(ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
        this.challengeId = id;
    }

    public UUID challengeId() {
        return challengeId;
    }
}
