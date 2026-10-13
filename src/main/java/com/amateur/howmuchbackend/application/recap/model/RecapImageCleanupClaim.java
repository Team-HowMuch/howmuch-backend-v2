package com.amateur.howmuchbackend.application.recap.model;

import java.util.UUID;

public record RecapImageCleanupClaim(UUID id, String fileName, UUID token) {}
