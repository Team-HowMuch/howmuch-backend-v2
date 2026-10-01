package com.amateur.howmuchbackend.application.port.out.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record AnalyticsSnapshot(
        Map<LocalDate, Long> signups,
        Map<LocalDate, Long> requests,
        Map<LocalDate, Long> closed,
        Map<String, Long> splitCounts,
        Map<LocalDate, BigDecimal> averageAmounts
) {}
