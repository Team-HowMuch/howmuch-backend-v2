package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementWidgetSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettlementWidgetRefreshServiceTest {
    private final CurrentActorPort actors = mock(CurrentActorPort.class);
    private final LoadSettlementWidgetPort widgets = mock(LoadSettlementWidgetPort.class);
    private final LoadSettlementPort settlements = mock(LoadSettlementPort.class);
    private final LoadSettlementReceivingAccountSnapshotPort snapshots = mock(LoadSettlementReceivingAccountSnapshotPort.class);
    private final ExternalConnectionStorePort connections = mock(ExternalConnectionStorePort.class);
    private final LoadAccountPort accounts = mock(LoadAccountPort.class);
    private final SettlementWidgetRefreshStorePort store = mock(SettlementWidgetRefreshStorePort.class);
    private final SettlementBankTransactionFetchService fetcher = mock(SettlementBankTransactionFetchService.class);
    private final SettlementDepositWriter writer = mock(SettlementDepositWriter.class);
    private final GetSettlementWidgetSummaryUseCase summaries = mock(GetSettlementWidgetSummaryUseCase.class);
    private final SettlementWidgetSummaryResult summary = mock(SettlementWidgetSummaryResult.class);
    private final List<SettlementWidgetReadModel> rows = new ArrayList<>();
    private final SettlementWidgetRefreshService service = new SettlementWidgetRefreshService(
            actors, widgets, settlements, snapshots, connections, accounts, store, fetcher, writer, summaries);

    @BeforeEach
    void setUp() {
        when(actors.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(widgets.findAllInProgress(1L)).thenReturn(rows);
        when(connections.findActiveByUserId(1L)).thenReturn(Optional.of(new ExternalConnection(1L, 1L, "synthetic")));
        when(accounts.findActiveByIdAndUserId(anyLong(), eq(1L))).thenReturn(Optional.of(mock(Account.class)));
        when(store.tryAcquire(eq(1L), anyString())).thenReturn(true);
        when(store.renew(eq(1L), anyString())).thenReturn(true);
        when(summaries.getSummary()).thenReturn(summary);
    }

    @Test
    void missingSnapshotDoesNotBlockAnotherTargetOnTheSameAccount() {
        UUID missing = target(10L);
        UUID valid = target(10L);
        when(snapshots.findBySettlementId(missing)).thenReturn(Optional.empty());
        assertThat(service.refresh()).isSameAs(summary);
        verify(writer, never()).reconcileForWidget(eq(missing), any(), any(), any(), any(), any());
        verify(writer).reconcileForWidget(eq(valid), eq(1L), any(), any(), any(), any());
        verify(store).finish(eq(1L), anyString(), isNotNull());
    }

    @Test
    void noSnapshotsReturnsSummaryWithoutBankOrSuccessTimestamp() {
        UUID id = target(10L);
        when(snapshots.findBySettlementId(id)).thenReturn(Optional.empty());
        assertThat(service.refresh()).isSameAs(summary);
        verifyNoInteractions(connections, accounts, fetcher, writer);
        verify(store).finish(eq(1L), anyString(), isNull());
    }

    @Test
    void missingConnectionFinishesBeforeReadingStoredSummary() {
        target(10L);
        when(connections.findActiveByUserId(1L)).thenReturn(Optional.empty());
        assertThat(service.refresh()).isSameAs(summary);
        verifyNoInteractions(accounts, fetcher, writer);
        var order = inOrder(store, summaries);
        order.verify(store).finish(eq(1L), anyString(), isNull());
        order.verify(summaries).getSummary();
    }

    @Test
    void inactiveAccountIsSkippedBeforeFetchingAndOtherAccountContinues() {
        UUID unavailable = target(10L);
        UUID valid = target(20L);
        when(accounts.findActiveByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());
        assertThat(service.refresh()).isSameAs(summary);
        verify(fetcher, never()).fetchForWidget(any(), eq(10L), any(), any(), any(), any(), any());
        verify(writer, never()).reconcileForWidget(eq(unavailable), any(), any(), any(), any(), any());
        verify(writer).reconcileForWidget(eq(valid), eq(1L), any(), any(), any(), any());
        verify(store).finish(eq(1L), anyString(), isNotNull());
    }

    @Test
    void allInactiveAccountsDoNotClaimSuccess() {
        target(10L);
        when(accounts.findActiveByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());
        assertThat(service.refresh()).isSameAs(summary);
        verifyNoInteractions(fetcher, writer);
        verify(store).finish(eq(1L), anyString(), isNull());
    }

    @ParameterizedTest
    @MethodSource("unavailableCodes")
    void unavailableWriterTargetDoesNotBlockNextTarget(StatusCode code) {
        UUID first = target(10L);
        UUID second = target(10L);
        writerFails(first, ApplicationException.of(code));
        assertThat(service.refresh()).isSameAs(summary);
        verify(writer).reconcileForWidget(eq(second), eq(1L), any(), any(), any(), any());
        verify(fetcher, times(1)).fetchForWidget(any(), eq(10L), any(), any(), any(), any(), any());
        verify(store).finish(eq(1L), anyString(), isNotNull());
    }

    @ParameterizedTest
    @MethodSource("unavailableCodes")
    void allSkippedWriterTargetsDoNotClaimSuccess(StatusCode code) {
        writerFails(target(10L), ApplicationException.of(code));
        assertThat(service.refresh()).isSameAs(summary);
        verify(store).finish(eq(1L), anyString(), isNull());
    }

    @ParameterizedTest
    @MethodSource("fatalCodes")
    void fatalWriterErrorAfterSuccessStillFailsRefresh(StatusCode code) {
        target(10L);
        writerFails(target(10L), ApplicationException.of(code));
        assertThatThrownBy(service::refresh).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(code));
        verify(store).finish(eq(1L), anyString(), isNull());
        verifyNoInteractions(summaries);
    }

    @ParameterizedTest
    @MethodSource("unavailableCodes")
    void targetCodesFromBankFetchAreNotSwallowed(StatusCode code) {
        target(10L);
        var failure = ApplicationException.of(code);
        when(fetcher.fetchForWidget(any(), anyLong(), any(), any(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(service::refresh).isSameAs(failure);
        verify(store).finish(eq(1L), anyString(), isNull());
        verifyNoInteractions(writer, summaries);
    }

    @Test
    void unexpectedWriterFailureIsNotSwallowed() {
        var failure = new IllegalStateException("synthetic");
        writerFails(target(10L), failure);
        assertThatThrownBy(service::refresh).isSameAs(failure);
        verify(store).finish(eq(1L), anyString(), isNull());
        verifyNoInteractions(summaries);
    }

    @Test
    void expiredLeaseAfterSuccessfulWriterDoesNotClaimSuccess() {
        target(10L);
        when(store.renew(eq(1L), anyString())).thenReturn(true, true, false);
        assertThatThrownBy(service::refresh).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        verify(writer).reconcileForWidget(any(), any(), any(), any(), any(), any());
        verify(store).finish(eq(1L), anyString(), isNull());
        verifyNoInteractions(summaries);
    }

    @Test
    void leaseFailureDuringBankFetchStopsBeforeWriter() {
        target(10L);
        when(store.renew(eq(1L), anyString())).thenReturn(true, false);
        when(fetcher.fetchForWidget(any(), anyLong(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            call.getArgument(6, Runnable.class).run();
            return List.of();
        });
        assertThatThrownBy(service::refresh).isInstanceOfSatisfying(ApplicationException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        verify(store).finish(eq(1L), anyString(), isNull());
        verifyNoInteractions(writer, summaries);
    }

    private UUID target(long accountId) {
        UUID id = UUID.randomUUID();
        var row = mock(SettlementWidgetReadModel.class);
        when(row.creator()).thenReturn(true);
        when(row.settlementId()).thenReturn(id);
        rows.add(row);
        var now = LocalDateTime.of(2026, 10, 1, 0, 0);
        when(settlements.findById(id)).thenReturn(Optional.of(new Settlement(
                id, 1L, "검증", 1000, SettlementStatus.AWAITING_DEPOSIT, now, now)));
        when(snapshots.findBySettlementId(id)).thenReturn(Optional.of(new SettlementReceivingAccountSnapshot(
                id, accountId, "0020", "synthetic-account", "검증", now)));
        return id;
    }

    private void writerFails(UUID id, RuntimeException failure) {
        when(writer.reconcileForWidget(eq(id), eq(1L), any(), any(), any(), any())).thenThrow(failure);
    }

    static Stream<StatusCode> unavailableCodes() {
        return Stream.of(CommonStatusCode.CONCURRENT_UPDATE, SettlementStatusCode.SETTLEMENT_NOT_FOUND,
                SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND,
                SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE);
    }

    static Stream<StatusCode> fatalCodes() {
        return Stream.of(CommonStatusCode.TOO_MANY_REQUEST, CommonStatusCode.TEMPORARILY_UNAVAILABLE,
                CommonStatusCode.FORBIDDEN);
    }
}
