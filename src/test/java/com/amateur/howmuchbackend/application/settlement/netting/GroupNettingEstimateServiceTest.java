package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.service.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult.Reason;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCompressionPolicy;

class GroupNettingEstimateServiceTest {
    @ParameterizedTest
    @CsvSource({
            "0,0,0,0,false,NOT_ENOUGH_TARGETS",
            "1,1,1,1,false,NOT_ENOUGH_TARGETS",
            "0,1,1,1,false,NOT_ENOUGH_TARGETS",
            "2,2,2,2,false,NO_REDUCTION",
            "2,2,1,2,false,",
            "2,2,0,2,false,",
            "1,2,1,2,false,",
            "2,2,1,2,true,PROCESSING",
            "1,1,1,1,true,PROCESSING",
            "0,0,0,0,true,PROCESSING"
    })
    void cachedCountsStillUseCurrentProcessingStatus(int eligible, int before, int after, int targets,
            boolean processing, Reason expected) {
        var actor = mock(CurrentActorPort.class);
        var groups = mock(GroupAccessService.class);
        var calculation = mock(NettingEstimateCalculationReader.class);
        var cache = mock(NettingEstimateCachePort.class);
        var availability = mock(NettingTransferAvailabilityPort.class);
        var debts = mock(LoadGroupNettingDebtPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(cache.find(1)).thenReturn(Optional.of(new NettingEstimateCachePort.Counts(eligible, before, after, targets)));
        when(availability.isGroupReplacementPending(1)).thenReturn(processing);
        var service = new GroupNettingEstimateService(actor, groups, calculation, cache, availability, debts,
                new SimpleMeterRegistry(), new NettingCompressionPolicy());
        var result = service.estimate(1);
        assertThat(result.reason()).isEqualTo(expected);
        assertThat(result.canCompress()).isEqualTo(expected == null);
        verifyNoInteractions(calculation);
        verify(groups, times(2)).requireActiveMember(1L, 1L);
        verify(debts, times(2)).hasOpenReviews(1);
    }

    @Test
    void processingReasonIsNotCachedAndOpenReviewsKeepExistingError() {
        var actor = mock(CurrentActorPort.class);
        var groups = mock(GroupAccessService.class);
        var calculation = mock(NettingEstimateCalculationReader.class);
        var cache = mock(NettingEstimateCachePort.class);
        var availability = mock(NettingTransferAvailabilityPort.class);
        var debts = mock(LoadGroupNettingDebtPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(cache.find(1)).thenReturn(Optional.of(new NettingEstimateCachePort.Counts(2, 2, 1, 2)));
        when(availability.isGroupReplacementPending(1)).thenReturn(true, false);
        var service = new GroupNettingEstimateService(actor, groups, calculation, cache, availability, debts,
                new SimpleMeterRegistry(), new NettingCompressionPolicy());
        assertThat(service.estimate(1).reason()).isEqualTo(Reason.PROCESSING);
        assertThat(service.estimate(1).reason()).isNull();
        when(debts.hasOpenReviews(1)).thenReturn(true);
        assertThatThrownBy(() -> service.estimate(1)).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(NettingStatusCode.NETTING_ALREADY_ACTIVE));
    }

    @Test
    void busyCacheHasBoundedWaitingAndDoesNotStartAnotherCalculation() {
        var actor = mock(CurrentActorPort.class);
        var groups = mock(GroupAccessService.class);
        var calculation = mock(NettingEstimateCalculationReader.class);
        var cache = mock(NettingEstimateCachePort.class);
        var availability = mock(NettingTransferAvailabilityPort.class);
        var debts = mock(LoadGroupNettingDebtPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(cache.find(1)).thenReturn(Optional.empty());
        when(cache.tryClaim(1)).thenReturn(Optional.empty());
        var service =
                new GroupNettingEstimateService(
                        actor,
                        groups,
                        calculation,
                        cache,
                        availability,
                        debts,
                        new SimpleMeterRegistry(),
                        new com.amateur.howmuchbackend.application.settlement.netting.policy
                                .NettingCompressionPolicy());
        assertThatThrownBy(() -> service.estimate(1))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_ESTIMATE_BUSY));
        verifyNoInteractions(calculation);
    }
}
