package com.amateur.howmuchbackend.application.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementUnpaidSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementUnpaidSummaryReadModel;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementUnpaidSummaryPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class SettlementUnpaidSummaryServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementUnpaidSummaryPort loadPort;

    @Test
    void unpaidSummary_currentActor_loadsOnlyOwnCreatedSettlements() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(42L));
        when(loadPort.findAllByCreatorId(42L)).thenReturn(List.of(new SettlementUnpaidSummaryReadModel(
                UUID.randomUUID(), SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY,
                UUID.randomUUID(), 10_000L, BigInteger.valueOf(3_000)
        )));
        var service = new SettlementUnpaidSummaryService(
                currentActorPort, loadPort, new SettlementUnpaidSummaryPolicy(),
                        id ->
                                new com.amateur.howmuchbackend.application.port.out.settlement
                                        .netting.LoadNettedUnpaidSummaryPort.Summary(0, 0));

        assertThat(service.getSummary()).isEqualTo(new SettlementUnpaidSummaryResult(7_000, 1, 0));
        verify(loadPort).findAllByCreatorId(42L);
    }
}
