package com.amateur.howmuchbackend.application.settlement.service;

import org.junit.jupiter.api.DisplayName;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort.RequestedItemReceivableFacts;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private LoadRequestedItemReceivableFactsPort loadRequestedItemReceivableFactsPort;

    @Mock
    private MySettlementCategoryReader myCategoryReader;

    @Mock
    private SettlementReminderCooldownReader cooldownReader;

    private SettlementListService service() {
        return new SettlementListService(
                currentActorPort,
                loadSettlementListPort,
                new RequestedItemReceivableCalculator(
                        loadRequestedItemReceivableFactsPort, new SettlementItemAllocationPolicy()),
                myCategoryReader,
                cooldownReader
        );
    }

    @Test
    @DisplayName("목록 전체의 내 카테고리를 한 번에 읽어 항목마다 붙이고, 없는 항목은 null이다")
    void settlementList_myCategoriesReadOnceForAllItems() {
        SettlementListService service = service();
        UUID other = UUID.fromString("5d1b0d4f-8f0b-4a51-9a52-7c9c8f9d0e11");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.PAY, SettlementListStatusFilter.IN_PROGRESS,
                null, null)).thenReturn(List.of(readModel(SETTLEMENT_ID), readModel(other)));
        SettlementCategoryResult cafe = new SettlementCategoryResult("CAFE_SNACK", "카페·간식", null, "cafe-snack");
        when(myCategoryReader.read(USER_ID, List.of(SETTLEMENT_ID, other))).thenReturn(Map.of(SETTLEMENT_ID, cafe));

        var result = service.get(new GetSettlementsCommand(
                SettlementListTab.PAY, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).extracting(SettlementListItemResult::myCategory).containsExactly(cafe, null);
        assertThat(result).extracting(SettlementListItemResult::reminderNextAvailableAt).containsOnlyNulls();
        verifyNoInteractions(cooldownReader);
        verify(myCategoryReader).read(USER_ID, List.of(SETTLEMENT_ID, other));
    }

    private static SettlementListReadModel readModel(UUID settlementId) {
        return new SettlementListReadModel(settlementId, SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0), null, null, 600L, SettlementAllocationType.DUTCH_PAY,
                3, 2, 0, 300L, null, "요청자", null, List.of());
    }

    @Test
    void settlementList_currentUser_preservesNumbersAndRawStatus() {
        SettlementListService service = service();
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
                400L,
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
                400L,
                null,
                null,
                List.of(new SettlementListItemResult.GroupSummary(
                        10L,
                        "여행 모임",
                        IMAGE_VERSION
                ))
        ));
        assertThat(result).singleElement().satisfies(item -> assertThat(item.storeName()).isNull());
    }

    @Test
    void settlementList_storeName_passedThrough() {
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.AWAITING_DEPOSIT, LocalDateTime.of(2026, 8, 13, 0, 0),
                null, null, 600L, SettlementAllocationType.DUTCH_PAY, 3, 2, 0, null, null,
                null, "행복식당", List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(
                USER_ID, SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null
        )).thenReturn(List.of(readModel));

        LocalDateTime nextAvailableAt = LocalDateTime.of(2026, 8, 13, 0, 30);
        when(cooldownReader.findLatestNextAvailableAt(Map.of(
                SETTLEMENT_ID, SettlementStatus.AWAITING_DEPOSIT)))
                .thenReturn(Map.of(SETTLEMENT_ID, nextAvailableAt));

        var result = service().get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.storeName()).isEqualTo("행복식당"));
        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.reminderNextAvailableAt()).isEqualTo(nextAvailableAt));
    }

    @Test
    void settlementList_monthAndCompletedAt_passedThrough() {
        var month = java.time.YearMonth.of(2026, 9);
        var completedAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.COMPLETED, completedAt.minusMonths(1), completedAt, completedAt,
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 1, null, 300L, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, 10L, month)).thenReturn(List.of(readModel));
        var service = service();
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
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 0, null, 300L, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null)).thenReturn(List.of(readModel));

        var result = service().get(
                new GetSettlementsCommand(
                        SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.endedAt()).isEqualTo(suspendedAt);
            assertThat(item.completedAt()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "PAY, ITEM_ALLOCATION, REQUESTED, null, 6000, true",
            "PAY, ITEM_ALLOCATION, SUSPENDED, null, 6000, true",
            "PAY, ITEM_ALLOCATION, AWAITING_DEPOSIT, null, null, false",
            "PAY, ITEM_ALLOCATION, COMPLETED, null, null, false",
            "PAY, DUTCH_PAY, REQUESTED, null, null, false",
            "PAY, DUTCH_PAY, SUSPENDED, null, null, false",
            "PAY, DUTCH_PAY, AWAITING_DEPOSIT, null, null, false",
            "PAY, DUTCH_PAY, COMPLETED, null, null, false",
            "PAY, null, REQUESTED, null, null, false",
            "PAY, ITEM_ALLOCATION, REQUESTED, 2000, 2000, false",
            "PAY, ITEM_ALLOCATION, SUSPENDED, 2000, 2000, false",
            "PAY, ITEM_ALLOCATION, AWAITING_DEPOSIT, 2000, 2000, false",
            "PAY, ITEM_ALLOCATION, COMPLETED, 2000, 2000, false",
            "RECEIVE, ITEM_ALLOCATION, REQUESTED, null, null, false",
            "RECEIVE, ITEM_ALLOCATION, SUSPENDED, 2000, null, false"
    }, nullValues = "null")
    void settlementList_rawAmounts_displayedByTabAndAllocationState(
            SettlementListTab tab,
            SettlementAllocationType allocationType,
            SettlementStatus status,
            Long shareAmount,
            Long expectedAmount,
            boolean expectedNeedsItemSelection
    ) {
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, status, LocalDateTime.of(2026, 9, 30, 0, 0), null, null,
                6_000L, allocationType, null, null, null, shareAmount,
                tab == SettlementListTab.RECEIVE ? 0L : null, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, tab, SettlementListStatusFilter.ALL, null, null))
                .thenReturn(List.of(readModel));

        var result = service().get(
                new GetSettlementsCommand(tab, SettlementListStatusFilter.ALL, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.amountToPay()).isEqualTo(expectedAmount);
            assertThat(item.needsItemSelection()).isEqualTo(expectedNeedsItemSelection);
            assertThat(item.totalAmount()).isEqualTo(tab == SettlementListTab.RECEIVE ? 6_000L : null);
        });
    }

    @Test
    @DisplayName("선택 대기 품목 정산의 받을 금액은 영수증 총액에서 생성자 몫을 뺀 값이다")
    void receiveList_requestedItemAllocation_receivableAmountRecalculated() {
        var readModel = receiveReadModel(SettlementAllocationType.ITEM_ALLOCATION, SettlementStatus.REQUESTED, 0L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null)).thenReturn(List.of(readModel));
        when(loadRequestedItemReceivableFactsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID)))
                .thenReturn(List.of(creatorPickedCola()));

        var result = service().get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.totalAmount()).isEqualTo(12_000L);
            assertThat(item.receivableAmount()).isEqualTo(10_000L);
        });
    }

    @Test
    @DisplayName("다시 계산할 대상이 아니면 생성자를 뺀 Share 합계를 받을 금액으로 쓰고 추가 조회하지 않는다")
    void receiveList_notRequestedItemAllocation_usesShareSum() {
        var dutchPay = receiveReadModel(SettlementAllocationType.DUTCH_PAY, SettlementStatus.AWAITING_DEPOSIT, 8_000L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null)).thenReturn(List.of(dutchPay));

        var result = service().get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement()
                .satisfies(item -> assertThat(item.receivableAmount()).isEqualTo(8_000L));
        verifyNoInteractions(loadRequestedItemReceivableFactsPort);
    }

    @Test
    @DisplayName("받을 금액 계산 재료가 없으면 Share 합계로 되돌린다")
    void receiveList_factsMissing_fallsBackToShareSum() {
        var readModel = receiveReadModel(SettlementAllocationType.ITEM_ALLOCATION, SettlementStatus.SUSPENDED, 3_000L);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.ALL, null, null)).thenReturn(List.of(readModel));
        when(loadRequestedItemReceivableFactsPort.findAllBySettlementIds(List.of(SETTLEMENT_ID))).thenReturn(List.of());

        var result = service().get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null));

        assertThat(result).singleElement()
                .satisfies(item -> assertThat(item.receivableAmount()).isEqualTo(3_000L));
    }

    @Test
    @DisplayName("PAY 탭의 받을 금액은 비우고 추가 조회하지 않는다")
    void payList_receivableAmountNull() {
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.REQUESTED, LocalDateTime.of(2026, 10, 1, 0, 0), null, null,
                12_000L, SettlementAllocationType.ITEM_ALLOCATION, null, null, null, null, null, "요청자", null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findAll(USER_ID, SettlementListTab.PAY,
                SettlementListStatusFilter.IN_PROGRESS, null, null)).thenReturn(List.of(readModel));

        var result = service().get(new GetSettlementsCommand(
                SettlementListTab.PAY, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement()
                .satisfies(item -> assertThat(item.receivableAmount()).isNull());
        verifyNoInteractions(loadRequestedItemReceivableFactsPort);
    }

    private SettlementListReadModel receiveReadModel(
            SettlementAllocationType allocationType,
            SettlementStatus status,
            long receivableShareAmount
    ) {
        return new SettlementListReadModel(
                SETTLEMENT_ID, status, LocalDateTime.of(2026, 10, 1, 0, 0), null, null,
                12_000L, allocationType, 3, 2, 0, null, receivableShareAmount, null, null, List.of());
    }

    /** 총액 12,000원 중 생성자가 콜라 2,000원을 골랐다. */
    private RequestedItemReceivableFacts creatorPickedCola() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 0, 0);
        UUID expenseId = UUID.randomUUID();
        UUID creatorParticipantId = UUID.randomUUID();
        SettlementItem pizza = new SettlementItem(UUID.randomUUID(), SETTLEMENT_ID, expenseId, "피자", 1, 10_000, 0, now, now);
        SettlementItem cola = new SettlementItem(UUID.randomUUID(), SETTLEMENT_ID, expenseId, "콜라", 1, 2_000, 1, now, now);
        return new RequestedItemReceivableFacts(
                SETTLEMENT_ID,
                12_000L,
                creatorParticipantId,
                List.of(pizza, cola),
                List.of(new SettlementItemSelection(UUID.randomUUID(), SETTLEMENT_ID, cola.id(), creatorParticipantId,
                        SettlementItemAllocationFraction.ONE, 1, now, now)),
                List.of()
        );
    }
}
