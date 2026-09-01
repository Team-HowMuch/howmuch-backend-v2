package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementListServiceTest {
    private static final Long USER_ID = 7L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadSettlementListPort loadSettlementListPort;

    @Test
    void loadsListForCurrentUserAndPreservesNumericFieldsAndRawStatus() {
        SettlementListService service = new SettlementListService(currentActorPort, loadSettlementListPort);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                600L,
                SettlementAllocationType.DUTCH_PAY,
                3,
                2,
                0,
                null,
                null,
                List.of(new SettlementListReadModel.GroupSummary(10L, "여행 모임", true))
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L
        ))
                .thenReturn(List.of(readModel));

        var result = service.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L
        ));

        verify(loadSettlementListPort).findAll(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L
        );
        assertThat(result).containsExactly(new SettlementListItemResult(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                600L,
                SettlementAllocationType.DUTCH_PAY,
                3,
                2,
                0,
                null,
                null,
                List.of(new SettlementListItemResult.GroupSummary(
                        10L,
                        "여행 모임",
                        true
                ))
        ));
    }
}
