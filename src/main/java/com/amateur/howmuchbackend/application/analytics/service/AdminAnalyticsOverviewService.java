package com.amateur.howmuchbackend.application.analytics.service;

import com.amateur.howmuchbackend.application.port.in.analytics.GetAdminAnalyticsOverviewUseCase;
import com.amateur.howmuchbackend.application.port.in.analytics.result.AdminAnalyticsOverviewResult;
import com.amateur.howmuchbackend.application.port.out.analytics.AnalyticsSnapshot;
import com.amateur.howmuchbackend.application.port.out.analytics.FetchAnalyticsPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAnalyticsOverviewService implements GetAdminAnalyticsOverviewUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_DAYS = 30;
    private static final String SIGNUP = "signupCompleted";
    private static final String REQUEST = "settlementRequestSent";
    private static final String CLOSED = "settlementClosed";

    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final FetchAnalyticsPort fetchAnalyticsPort;

    @Override
    public AdminAnalyticsOverviewResult get(LocalDate from, LocalDate to) {
        long actorId = currentActorPort.getCurrentActor().userId();
        var admin = loadUserPort.findById(actorId)
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.FORBIDDEN));
        if (!admin.active() || admin.role() != UserRole.ADMIN) {
            throw ApplicationException.of(CommonStatusCode.FORBIDDEN);
        }

        if (from == null && to == null) {
            to = LocalDate.now(SEOUL);
            from = to.minusDays(MAX_DAYS - 1);
        } else if (from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApplicationException.of(CommonStatusCode.INVALID_ARGUMENT);
        }

        AnalyticsSnapshot snapshot = fetchAnalyticsPort.fetch(from, to);
        List<AdminAnalyticsOverviewResult.DailyEventCounts> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put(SIGNUP, snapshot.signups().getOrDefault(date, 0L));
            counts.put(REQUEST, snapshot.requests().getOrDefault(date, 0L));
            counts.put(CLOSED, snapshot.closed().getOrDefault(date, 0L));
            days.add(new AdminAnalyticsOverviewResult.DailyEventCounts(date, counts));
        }

        List<AdminAnalyticsOverviewResult.EventCount> kpis = List.of(
                eventCount(SIGNUP, "가입 완료", snapshot.signups()),
                eventCount(REQUEST, "정산 요청 발송", snapshot.requests()),
                eventCount(CLOSED, "정산 종료", snapshot.closed())
        );
        List<AdminAnalyticsOverviewResult.SplitCount> splits = List.of(
                new AdminAnalyticsOverviewResult.SplitCount("DUTCH_PAY", "더치페이",
                        snapshot.splitCounts().getOrDefault("dutch", 0L)),
                new AdminAnalyticsOverviewResult.SplitCount("ITEM_BASED", "품목별",
                        snapshot.splitCounts().getOrDefault("itemized", 0L))
        );
        LocalDate rangeFrom = from;
        LocalDate rangeTo = to;
        var averages = snapshot.averageAmounts().entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(rangeFrom) && !entry.getKey().isAfter(rangeTo))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AdminAnalyticsOverviewResult.AverageAmount(entry.getKey(), entry.getValue()))
                .toList();
        return new AdminAnalyticsOverviewResult(new AdminAnalyticsOverviewResult.Period(from, to),
                kpis, List.copyOf(days), kpis, splits, averages);
    }

    private AdminAnalyticsOverviewResult.EventCount eventCount(String key, String label, Map<LocalDate, Long> counts) {
        return new AdminAnalyticsOverviewResult.EventCount(key, label,
                counts.values().stream().mapToLong(Long::longValue).sum());
    }
}
