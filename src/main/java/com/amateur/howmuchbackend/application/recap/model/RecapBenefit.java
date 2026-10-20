package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;

public record RecapBenefit(String title, String reason, LocalDateTime expiresAt, String url, String logoUrl) {}
