package com.amateur.howmuchbackend.application.recap.model;

import java.time.YearMonth;
import java.util.UUID;

public record RecapGenerationClaim(UUID id, Long ownerId, YearMonth month, UUID token, int attemptCount) {}
