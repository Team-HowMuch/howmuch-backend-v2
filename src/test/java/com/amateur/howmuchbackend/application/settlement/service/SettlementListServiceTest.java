package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementListServiceTest {
    private static final Long USER_ID = 7L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadSettlementListPort loadSettlementListPort;

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 50})
    void returnsOnlyRequestedSizeAndUsesLastReturnedItemForCursor(int size) {
        var time = LocalDateTime.of(2026, 9, 10, 1, 2, 3, 123456000);
        var rows = java.util.stream.IntStream.rangeClosed(0, size)
                .mapToObj(index -> listItem(new UUID(0, size + 1L - index), time)).toList();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findPage(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null, null, null, size + 1))
                .thenReturn(rows);

        var page = new SettlementListService(currentActorPort, loadSettlementListPort)
                .get(new GetSettlementsCommand(null, null, null, null, null, size));

        assertThat(page.items()).hasSize(size);
        assertThat(page.hasNext()).isTrue();
        assertThat(SettlementListCursor.decode(page.nextCursor()))
                .isEqualTo(new SettlementListCursor(time, rows.get(size - 1).settlementId()));
    }

    @Test
    void passesCursorAndFiltersToNextPageAndEndsOnAnExactlyFullPage() {
        var time = LocalDateTime.of(2026, 9, 10, 1, 2, 3, 123456000);
        var month = java.time.YearMonth.of(2026, 9);
        String cursor = new SettlementListCursor(time, SETTLEMENT_ID).encode();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findPage(USER_ID, SettlementListTab.PAY,
                SettlementListStatusFilter.COMPLETED, 10L, month, time, SETTLEMENT_ID, 2))
                .thenReturn(List.of(listItem(new UUID(0, 1), time.minusSeconds(1))));

        var page = new SettlementListService(currentActorPort, loadSettlementListPort).get(
                new GetSettlementsCommand(SettlementListTab.PAY, SettlementListStatusFilter.COMPLETED,
                        10L, month, cursor, 1));

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void emptyPageHasNoNextCursorAndUsesDefaultSize() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findPage(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS, null, null, null, null, 21)).thenReturn(List.of());
        var page = new SettlementListService(currentActorPort, loadSettlementListPort)
                .get(new GetSettlementsCommand(null, null, null, null, null, null));
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 51, Integer.MAX_VALUE})
    void rejectsInvalidSizeBeforePersistence(int size) {
        assertThatThrownBy(() -> new GetSettlementsCommand(null, null, null, null, null, size))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PAGE_SIZE));
        verifyNoInteractions(loadSettlementListPort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-base64!", "2026-09-10T00:00|not-a-uuid",
            "2026-09-10T00:00", "+999999999-01-01T00:00|44c9323d-6a65-4a93-aeaa-ef8f24341c8a",
            "0000-01-01T00:00|44c9323d-6a65-4a93-aeaa-ef8f24341c8a",
            "2026-09-10T00:00|44c9323d-6a65-4a93-aeaa-ef8f24341c8a|extra"})
    void rejectsMalformedCursorBeforePersistence(String value) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        var service = new SettlementListService(currentActorPort, loadSettlementListPort);
        for (String cursor : List.of(value, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(() -> service.get(new GetSettlementsCommand(null, null, null, null, cursor, 20)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_CURSOR));
        }
        verifyNoInteractions(loadSettlementListPort);
    }

    private SettlementListReadModel listItem(UUID id, LocalDateTime time) {
        return new SettlementListReadModel(id, SettlementStatus.AWAITING_DEPOSIT, time, null,
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 1, null, null, List.of());
    }

    @Test
    void loadsListForCurrentUserAndPreservesNumericFieldsAndRawStatus() {
        SettlementListService service = new SettlementListService(currentActorPort, loadSettlementListPort);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                null,
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
        when(loadSettlementListPort.findPage(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null, null, null, 21))
                .thenReturn(List.of(readModel));

        var result = service.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null, null, null));

        verify(loadSettlementListPort).findPage(
                USER_ID,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null, null, null, 21);
        assertThat(result.items()).containsExactly(new SettlementListItemResult(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
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
                        true
                ))
        ));
    }
    @Test
    void passesMonthAndCompletedTimestampThrough() {
        var month = java.time.YearMonth.of(2026, 9);
        var completedAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        var readModel = new SettlementListReadModel(
                SETTLEMENT_ID, SettlementStatus.COMPLETED, completedAt.minusMonths(1), completedAt,
                600L, SettlementAllocationType.DUTCH_PAY, 2, 1, 1, null, null, List.of());
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadSettlementListPort.findPage(USER_ID, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, 10L, month, null, null, 21)).thenReturn(List.of(readModel));
        var service = new SettlementListService(currentActorPort, loadSettlementListPort);
        assertThat(service.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED, 10L, month, null, null)).items())
                .singleElement().satisfies(item -> {
                    assertThat(item.completedAt()).isEqualTo(completedAt);
                    assertThat(item.requestedAt()).isEqualTo(completedAt.minusMonths(1));
                });
    }
}
