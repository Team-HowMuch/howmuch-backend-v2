package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

class NettingEstimateInvalidationServiceTest {
    @Test
    void coalescesGroupsWithinTransactionAndInvalidatesOnlyAfterCommit() {
        var participants = mock(LoadSettlementParticipantPort.class);
        var cache = mock(NettingEstimateCachePort.class);
        var service = new NettingEstimateInvalidationService(participants, cache, true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.groupChanged(1);
            service.groupChanged(1);
            service.groupChanged(2);
            verifyNoInteractions(cache);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(cache, times(1)).invalidate(1);
            verify(cache, times(1)).invalidate(2);
            verifyNoMoreInteractions(cache);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void disabledFeatureDoesNotAddRedisOrGroupLookupWorkToExistingWrites() {
        var participants = mock(LoadSettlementParticipantPort.class);
        var cache = mock(NettingEstimateCachePort.class);
        var service = new NettingEstimateInvalidationService(participants, cache, false);
        service.settlementChanged(UUID.randomUUID());
        service.groupChanged(1);
        verifyNoInteractions(cache, participants);
    }
}
