package com.amateur.howmuchbackend.application.port.in.external;

import com.amateur.howmuchbackend.application.port.in.external.result.AdditionalAuthenticationResult;

import java.util.UUID;

public interface CancelAdditionalAuthenticationUseCase {
    AdditionalAuthenticationResult cancel(UUID challengeId);
}
