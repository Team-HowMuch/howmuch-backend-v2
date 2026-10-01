package com.amateur.howmuchbackend.adapter.in.web.analytics;

import com.amateur.howmuchbackend.adapter.in.web.analytics.docs.AdminAnalyticsDocs;
import com.amateur.howmuchbackend.adapter.in.web.analytics.dto.response.AdminAnalyticsOverviewWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.analytics.GetAdminAnalyticsOverviewUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin/analytics")
public class AdminAnalyticsController implements AdminAnalyticsDocs {
    private final GetAdminAnalyticsOverviewUseCase useCase;

    @Override
    @GetMapping("/overview")
    public ApiResponse<AdminAnalyticsOverviewWebResponse> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.success(AdminAnalyticsOverviewWebResponse.from(useCase.get(from, to)));
    }
}
