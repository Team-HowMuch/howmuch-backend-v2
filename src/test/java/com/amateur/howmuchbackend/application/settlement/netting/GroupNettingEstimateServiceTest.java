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

class GroupNettingEstimateServiceTest {
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
