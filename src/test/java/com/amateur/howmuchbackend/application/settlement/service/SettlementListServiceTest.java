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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private static final String IMAGE_VERSION = "image-version";
    private static final Long USER_ID = 7L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadSettlementListPort loadSettlementListPort;

    @Test
    void settlementList_currentUser_preservesNumbersAndRawStatus() {
        SettlementListService service = new SettlementListService(currentActorPort, loadSettlementListPort);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                null,
                null,
                600L,
                SettlementAllocationType.DUTCH_PAY,
                3,
                2,
                0,
                null,
                null,
                List.of(new SettlementListReadModel.GroupSummary(
                        10L,
                        "여행 모임",
                        IMAGE_VERSION
                ))
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null
        ))
                .thenReturn(List.of(readModel));

        var result = service.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null
        ));

        verify(loadSettlementListPort).findAll(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null
        );
        assertThat(result).containsExactly(new SettlementListItemResult(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                null,
                null,
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
                        IMAGE_VERSION
                ))
        ));
    }
    @Test
    void settlementList_monthAndCompletedAt_passedThrough() {
        var month = java.time.YearMonth.of(2026, 9);
        var completedAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.COMPLETED, completedAt.minusMonths(1), completedAt, completedAt,
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 1, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, 10L, month)).thenReturn(List.of(readModel));
        var service = new SettlementListService(currentActorPort, loadSettlementListPort);
        assertThat(service.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED, 10L, month)))
                .singleElement().satisfies(item -> {
                    assertThat(item.endedAt()).isEqualTo(completedAt);
                    assertThat(item.completedAt()).isEqualTo(completedAt);
                    assertThat(item.requestedAt()).isEqualTo(completedAt.minusMonths(1));
        });
    }

    @Test
    void settlementList_suspendedEndedAt_passedThroughWithoutCompletedAt() {
        var suspendedAt = LocalDateTime.of(2026, 9, 2, 12, 30);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.SUSPENDED, suspendedAt.minusDays(1), suspendedAt, null,
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 0, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null)).thenReturn(List.of(readModel));

        var result = new SettlementListService(currentActorPort, loadSettlementListPort).get(
                new GetSettlementsCommand(
                        SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.endedAt()).isEqualTo(suspendedAt);
            assertThat(item.completedAt()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "PAY, ITEM_ALLOCATION, REQUESTED, null, 6000",
            "PAY, ITEM_ALLOCATION, SUSPENDED, null, 6000",
            "PAY, ITEM_ALLOCATION, AWAITING_DEPOSIT, null, null",
            "PAY, ITEM_ALLOCATION, COMPLETED, null, null",
            "PAY, DUTCH_PAY, REQUESTED, null, null",
            "PAY, DUTCH_PAY, SUSPENDED, null, null",
            "PAY, DUTCH_PAY, AWAITING_DEPOSIT, null, null",
            "PAY, DUTCH_PAY, COMPLETED, null, null",
            "PAY, null, REQUESTED, null, null",
            "PAY, ITEM_ALLOCATION, REQUESTED, 2000, 2000",
            "PAY, ITEM_ALLOCATION, SUSPENDED, 2000, 2000",
            "PAY, ITEM_ALLOCATION, AWAITING_DEPOSIT, 2000, 2000",
            "PAY, ITEM_ALLOCATION, COMPLETED, 2000, 2000",
            "RECEIVE, ITEM_ALLOCATION, REQUESTED, null, null",
            "RECEIVE, ITEM_ALLOCATION, SUSPENDED, 2000, null"
    }, nullValues = "null")
    void settlementList_rawAmounts_displayedByTabAndAllocationState(
            SettlementListTab tab,
            SettlementAllocationType allocationType,
            SettlementStatus status,
            Long shareAmount,
            Long expectedAmount
    ) {
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, status, LocalDateTime.of(2026, 9, 30, 0, 0), null, null,
                6_000L, allocationType, null, null, null, shareAmount, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, tab, SettlementListStatusFilter.ALL, null, null))
                .thenReturn(List.of(readModel));

        var result = new SettlementListService(currentActorPort, loadSettlementListPort).get(
                new GetSettlementsCommand(tab, SettlementListStatusFilter.ALL, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.amountToPay()).isEqualTo(expectedAmount);
            assertThat(item.totalAmount()).isEqualTo(tab == SettlementListTab.RECEIVE ? 6_000L : null);
        });
    }
}
