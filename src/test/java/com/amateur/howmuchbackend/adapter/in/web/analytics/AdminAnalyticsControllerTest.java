package com.amateur.howmuchbackend.adapter.in.web.analytics;

import com.amateur.howmuchbackend.application.port.in.analytics.GetAdminAnalyticsOverviewUseCase;
import com.amateur.howmuchbackend.application.port.in.analytics.result.AdminAnalyticsOverviewResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAnalyticsControllerTest {
    private final GetAdminAnalyticsOverviewUseCase useCase = mock(GetAdminAnalyticsOverviewUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAnalyticsController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsFrontendShapeAndForwardsDates() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 30);
        when(useCase.get(date, date)).thenReturn(new AdminAnalyticsOverviewResult(
                new AdminAnalyticsOverviewResult.Period(date, date),
                List.of(new AdminAnalyticsOverviewResult.EventCount("signupCompleted", "가입 완료", 1)),
                List.of(new AdminAnalyticsOverviewResult.DailyEventCounts(date, Map.of("signupCompleted", 1L))),
                List.of(new AdminAnalyticsOverviewResult.EventCount("signupCompleted", "가입 완료", 1)),
                List.of(), List.of()));

        mvc.perform(get("/api/v2/admin/analytics/overview")
                        .param("from", "2026-09-30").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.period.from").value("2026-09-30"))
                .andExpect(jsonPath("$.data.kpis[0].eventKey").value("signupCompleted"))
                .andExpect(jsonPath("$.data.eventTimeSeries[0].counts.signupCompleted").value(1));
        verify(useCase).get(date, date);
    }

    @Test
    void invalidDateReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/v2/admin/analytics/overview").param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }
}
