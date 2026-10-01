package com.amateur.howmuchbackend.application.port.in.analytics.result;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AdminAnalyticsOverviewResult(
        Period period,
        List<EventCount> kpis,
        List<DailyEventCounts> eventTimeSeries,
        List<EventCount> signupToSettlementFunnel,
        List<SplitCount> splitTypeDistribution,
        List<AverageAmount> averageAmountTrend
) {
    public record Period(LocalDate from, LocalDate to) {}
    public record EventCount(String eventKey, String label, long count) {}
    public record DailyEventCounts(LocalDate date, Map<String, Long> counts) {}
    public record SplitCount(String splitType, String label, long count) {}
    public record AverageAmount(LocalDate date, BigDecimal averageAmount) {}
}
