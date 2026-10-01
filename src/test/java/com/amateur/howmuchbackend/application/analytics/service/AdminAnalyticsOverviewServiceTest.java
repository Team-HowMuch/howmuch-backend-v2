package com.amateur.howmuchbackend.application.analytics.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.analytics.AnalyticsSnapshot;
import com.amateur.howmuchbackend.application.port.out.analytics.FetchAnalyticsPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsOverviewServiceTest {
    @Mock CurrentActorPort actors;
    @Mock LoadUserPort users;
    @Mock FetchAnalyticsPort analytics;

    @Test
    void fillsMissingEventDaysAndOmitsMissingAverageDays() {
        givenRole(UserRole.ADMIN);
        LocalDate from = LocalDate.of(2026, 9, 29);
        LocalDate to = from.plusDays(2);
        when(analytics.fetch(from, to)).thenReturn(new AnalyticsSnapshot(
                Map.of(from, 2L), Map.of(from.plusDays(1), 3L), Map.of(),
                Map.of("dutch", 2L, "itemized", 1L, "unknown", 1L),
                Map.of(from.plusDays(1), new BigDecimal("1250.50"))));

        var result = service().get(from, to);

        assertThat(result.eventTimeSeries()).hasSize(3);
        assertThat(result.eventTimeSeries().get(2).counts())
                .containsEntry("signupCompleted", 0L)
                .containsEntry("settlementRequestSent", 0L)
                .containsEntry("settlementClosed", 0L);
        assertThat(result.kpis()).extracting(item -> item.count()).containsExactly(2L, 3L, 0L);
        assertThat(result.signupToSettlementFunnel()).isEqualTo(result.kpis());
        assertThat(result.splitTypeDistribution()).extracting(item -> item.count()).containsExactly(2L, 1L);
        assertThat(result.averageAmountTrend()).hasSize(1);
        assertThat(result.averageAmountTrend().get(0).date()).isEqualTo(from.plusDays(1));
    }

    @Test
    void rejectsNonAdminBeforeExternalCall() {
        givenRole(UserRole.USER);
        assertThatThrownBy(() -> service().get(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(CommonStatusCode.FORBIDDEN));
        verifyNoInteractions(analytics);
    }

    @Test
    void rejectsPartialAndLongRanges() {
        givenRole(UserRole.ADMIN);
        assertThatThrownBy(() -> service().get(null, LocalDate.of(2026, 9, 30)))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> service().get(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ApplicationException.class);
        verifyNoInteractions(analytics);
    }

    private AdminAnalyticsOverviewService service() {
        return new AdminAnalyticsOverviewService(actors, users, analytics);
    }

    private void givenRole(UserRole role) {
        User user = mock(User.class);
        when(user.active()).thenReturn(true);
        when(user.role()).thenReturn(role);
        when(actors.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(users.findById(1L)).thenReturn(Optional.of(user));
    }
}
