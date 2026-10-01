package com.amateur.howmuchbackend.application.port.in.analytics;

import com.amateur.howmuchbackend.application.port.in.analytics.result.AdminAnalyticsOverviewResult;

import java.time.LocalDate;

public interface GetAdminAnalyticsOverviewUseCase {
    AdminAnalyticsOverviewResult get(LocalDate from, LocalDate to);
}
