package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

public record MonthlyRecap(UUID id, Long ownerId, YearMonth yearMonth, String ownerName,
                           RecapSummary summary, RecapComparison comparison,
                           RecapCompanion companion, LocalDateTime generatedAt) {
    public MonthlyRecap withCompanionAvatar(String fileName) {
        return new MonthlyRecap(id, ownerId, yearMonth, ownerName, summary, comparison,
                companion == null ? null : companion.withAvatarFileName(fileName), generatedAt);
    }
}
