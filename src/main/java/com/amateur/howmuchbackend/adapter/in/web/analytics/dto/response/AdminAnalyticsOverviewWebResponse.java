package com.amateur.howmuchbackend.adapter.in.web.analytics.dto.response;

import com.amateur.howmuchbackend.application.port.in.analytics.result.AdminAnalyticsOverviewResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record AdminAnalyticsOverviewWebResponse(
        Period period,
        List<EventCount> kpis,
        List<DailyEventCounts> eventTimeSeries,
        List<EventCount> signupToSettlementFunnel,
        List<SplitCount> splitTypeDistribution,
        List<AverageAmount> averageAmountTrend
) {
    public static AdminAnalyticsOverviewWebResponse from(AdminAnalyticsOverviewResult result) {
        List<EventCount> counts = result.kpis().stream()
                .map(item -> new EventCount(item.eventKey(), item.label(), item.count()))
                .toList();
        return new AdminAnalyticsOverviewWebResponse(
                new Period(result.period().from(), result.period().to()),
                counts,
                result.eventTimeSeries().stream()
                        .map(item -> new DailyEventCounts(item.date(), item.counts())).toList(),
                counts,
                result.splitTypeDistribution().stream()
                        .map(item -> new SplitCount(item.splitType(), item.label(), item.count())).toList(),
                result.averageAmountTrend().stream()
                        .map(item -> new AverageAmount(item.date(), item.averageAmount())).toList()
        );
    }

    public record Period(LocalDate from, LocalDate to) {}
    public record EventCount(String eventKey, String label, long count) {}
    public record DailyEventCounts(LocalDate date, Map<String, Long> counts) {}
    public record SplitCount(String splitType, String label, long count) {}
    public record AverageAmount(LocalDate date, BigDecimal averageAmount) {}
}
