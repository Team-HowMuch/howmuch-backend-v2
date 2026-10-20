package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record MonthlyRecap(UUID id, Long ownerId, YearMonth yearMonth, String ownerName,
                           RecapSummary summary, RecapComparison comparison,
                           RecapCompanion companion, LocalDateTime generatedAt,
                           List<RecapCategoryTotal> categories, RecapRecord record, List<RecapBenefit> benefits) {
    public MonthlyRecap {
        categories = categories == null ? null : List.copyOf(categories);
        benefits = benefits == null ? null : List.copyOf(benefits);
    }

    public MonthlyRecap withCompanionAvatar(String fileName) {
        return new MonthlyRecap(id, ownerId, yearMonth, ownerName, summary, comparison,
                companion == null ? null : companion.withAvatarFileName(fileName), generatedAt,
                categories, record, benefits);
    }
}
