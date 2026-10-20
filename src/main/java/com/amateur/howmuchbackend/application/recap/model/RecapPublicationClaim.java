package com.amateur.howmuchbackend.application.recap.model;

import java.util.UUID;

public record RecapPublicationClaim(RecapPublicationMessage message, UUID leaseToken, int attemptCount) {}
