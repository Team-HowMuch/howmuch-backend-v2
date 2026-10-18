package com.amateur.howmuchbackend.application.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 거래 명시적 동기화 Application 흐름")
class AccountTransactionSyncServiceTest {
    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 1L;
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-13T03:00:00Z"), ZoneOffset.UTC);
    private static final String CLAIM_TOKEN = "claim-token";

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadAccountTransactionPort loadTransactionPort;
    @Mock private AccountTransactionRefreshStorePort refreshStorePort;
    @Mock private AccountTransactionCoveragePort coveragePort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private BankTransactionPort bankTransactionPort;
    @Mock private BankCatalog bankCatalog;
    @Mock private AccountCredentialProtectionService credentialProtection;
    @Mock private AccountTransactionSyncWriter writer;

    private AccountTransactionSyncService service;

    @BeforeEach
    void setUp() {
        service = new AccountTransactionSyncService(
                currentActorPort, loadAccountPort, loadTransactionPort, refreshStorePort, coveragePort,
                connectionStorePort, bankTransactionPort, bankCatalog, credentialProtection, writer, CLOCK
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    @Test
    @DisplayName("단일 월 동기화는 CODEF를 한 번 호출하고 저장 결과를 반환한다")
    void sync_singleMonth_fetchesOnceAndPersists() {
        Account account = account("11");
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, ACCOUNT_ID, AUGUST, new BigDecimal("12500"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                "CID-1", "0020", account.externalAccountKey(), AUGUST.atDay(1), AUGUST.atEndOfMonth(), null
        )).thenReturn(List.of(new BankTransactionPort.BankTransaction(
                AccountTransactionType.EXPENSE,
                new BigDecimal("12500"),
                LocalDateTime.of(2026, 8, 20, 0, 15),
                "편의점 생활비",
                new BigDecimal("1250000")
        )));
        when(writer.persist(eq(USER_ID), eq(ACCOUNT_ID), eq(AUGUST), anyList(), any(LocalDateTime.class)))
                .thenReturn(summary);

        AccountTransactionSyncResult result = service.sync(ACCOUNT_ID, AUGUST);

        assertThat(result.refreshed()).isTrue();
        assertThat(result.syncInProgress()).isFalse();
        assertThat(result.fetchedCount()).isOne();
        assertThat(result.fetchedFrom()).isEqualTo(AUGUST.atDay(1));
        assertThat(result.fetchedTo()).isEqualTo(AUGUST.atEndOfMonth());
        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500");
        verify(bankTransactionPort).fetch(
                "CID-1", "0020", account.externalAccountKey(), AUGUST.atDay(1), AUGUST.atEndOfMonth(), null
        );
        ArgumentCaptor<List<com.amateur.howmuchbackend.domain.account.Transaction>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(writer).persist(eq(USER_ID), eq(ACCOUNT_ID), eq(AUGUST), captor.capture(), any(LocalDateTime.class));
        assertThat(captor.getValue()).singleElement().satisfies(transaction -> {
            assertThat(transaction.externalTransactionKey()).matches("[0-9a-f]{64}");
            assertThat(transaction.accountId()).isEqualTo(ACCOUNT_ID);
        });
        var order = org.mockito.Mockito.inOrder(writer, coveragePort);
        order.verify(writer).persist(any(), any(), any(), anyList(), any());
        order.verify(coveragePort).markCovered(any(), any(), any(), any(), any(), any());
        verify(refreshStorePort).markFresh(ACCOUNT_ID, AUGUST, Duration.ofMinutes(1));
        verify(coveragePort).markCovered(
                eq(ACCOUNT_ID), eq(AUGUST), eq(AUGUST.atDay(1)), eq(AUGUST.atEndOfMonth()), any(LocalDateTime.class), eq(Duration.ofDays(30)));
        verify(refreshStorePort).release(ACCOUNT_ID, AUGUST, CLAIM_TOKEN);
    }

    @Test
    @DisplayName("중복 방지 구간 안에서는 CODEF를 호출하지 않는다")
    void sync_freshThrottle_skipsCodef() {
        Account account = account("11");
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, ACCOUNT_ID, AUGUST, new BigDecimal("12500"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(true);
        when(coveragePort.findCoveredAt(ACCOUNT_ID, AUGUST)).thenReturn(Optional.of(LocalDateTime.of(2026, 9, 13, 3, 0)));
        when(coveragePort.coversMonth(ACCOUNT_ID, AUGUST)).thenReturn(true);
        when(loadTransactionPort.findSummary(ACCOUNT_ID, AUGUST)).thenReturn(Optional.of(summary));

        AccountTransactionSyncResult result = service.sync(ACCOUNT_ID, AUGUST);

        assertThat(result.refreshed()).isFalse();
        assertThat(result.syncInProgress()).isFalse();
        assertThat(result.fetchedCount()).isZero();
        assertThat(result.syncedAt()).isEqualTo(LocalDateTime.of(2026, 9, 13, 3, 0));
        verifyNoInteractions(bankTransactionPort, connectionStorePort, writer);
        verify(refreshStorePort, never()).tryClaim(any(), any(), any());
        verify(refreshStorePort, never()).markFresh(any(), any(), any());
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 계좌·월 동기화가 진행 중이면 CODEF 없이 진행 중 결과를 반환한다")
    void sync_alreadyInProgress_skipsCodef() {
        Account account = account("11");
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, ACCOUNT_ID, AUGUST, new BigDecimal("12500"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.empty());
        when(loadTransactionPort.findSummary(ACCOUNT_ID, AUGUST)).thenReturn(Optional.of(summary));

        AccountTransactionSyncResult result = service.sync(ACCOUNT_ID, AUGUST);

        assertThat(result.refreshed()).isFalse();
        assertThat(result.syncInProgress()).isTrue();
        assertThat(result.fetchedCount()).isZero();
        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500");
        verifyNoInteractions(bankTransactionPort, connectionStorePort, writer);
        verify(refreshStorePort, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("과거 월 부분 수집은 refresh marker가 남아 있어도 재수집 claim을 시도한다")
    void sync_partialPastMonth_doesNotThrottle() {
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account("11")));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(true);
        when(coveragePort.findCoveredAt(ACCOUNT_ID, AUGUST)).thenReturn(Optional.of(LocalDateTime.of(2026, 8, 31, 14, 59)));
        when(coveragePort.coversMonth(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.empty());
        when(loadTransactionPort.calculateSummary(ACCOUNT_ID, AUGUST)).thenReturn(
                new AccountMonthlySummary(null, ACCOUNT_ID, AUGUST, BigDecimal.ZERO, 0, null));
        assertThat(service.sync(ACCOUNT_ID, AUGUST).syncInProgress()).isTrue();
        verify(refreshStorePort).tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any());
    }

    @Test
    @DisplayName("미래 월은 CODEF 호출 전에 거절한다")
    void sync_futureMonth_rejectedBeforeCodef() {
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, YearMonth.of(2026, 10)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.INVALID_ACCOUNT_QUERY));
        verifyNoInteractions(bankTransactionPort, connectionStorePort, writer);
        verify(refreshStorePort, never()).tryClaim(any(), any(), any());
    }

    @Test
    @DisplayName("거래내역 미지원 상품은 CODEF 호출 전에 거절한다")
    void sync_unsupportedProduct_rejectedBeforeCodef() {
        Account account = account("12");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED));
        verifyNoInteractions(bankTransactionPort, connectionStorePort, writer);
        verify(refreshStorePort, never()).tryClaim(any(), any(), any());
    }

    @Test
    @DisplayName("활성 연결이 없으면 CODEF 호출 없이 연결 오류를 반환한다")
    void sync_connectionMissing_failsWithoutPersist() {
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND));
        verifyNoInteractions(bankTransactionPort, writer);
        verify(refreshStorePort, never()).markFresh(any(), any(), any());
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
        verify(refreshStorePort).release(ACCOUNT_ID, AUGUST, CLAIM_TOKEN);
    }

    @Test
    @DisplayName("CODEF 조회 실패는 저장 없이 그대로 전파한다")
    void sync_codefFailure_propagatesWithoutPersist() {
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                "CID-1", "0020", account.externalAccountKey(), AUGUST.atDay(1), AUGUST.atEndOfMonth(), null
        )).thenThrow(ApplicationException.of(ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST))
                .isInstanceOf(ApplicationException.class);
        verify(writer, never()).persist(any(), any(), any(), anyList(), any());
        verify(refreshStorePort, never()).markFresh(any(), any(), any());
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
        verify(refreshStorePort).release(ACCOUNT_ID, AUGUST, CLAIM_TOKEN);
    }

    @Test
    @DisplayName("저장 시점에 계좌가 비활성이면 stale 결과를 반영하지 않는다")
    void sync_staleAccountDuringPersist_notApplied() {
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.isFresh(ACCOUNT_ID, AUGUST)).thenReturn(false);
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(writer.persist(eq(USER_ID), eq(ACCOUNT_ID), eq(AUGUST), anyList(), any(LocalDateTime.class)))
                .thenThrow(ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));

        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));
        verify(refreshStorePort, never()).markFresh(any(), any(), any());
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
        verify(refreshStorePort).release(ACCOUNT_ID, AUGUST, CLAIM_TOKEN);
    }

    @Test
    void smsRequestKeepsOriginalCollectionRangeAndReleasesClaim() {
        Account account = account("11");
        var auth = org.mockito.Mockito.mock(com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService.class);
        service.authentication(auth);
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(auth.withTransactionQueryAdmission(eq(USER_ID), eq("0020"), any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        var request = new com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationRequest(new byte[]{1}, "***", 120, null);
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any())).thenThrow(
                new com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationRequiredException(request));
        var challenge = java.util.UUID.randomUUID();
        when(auth.suspendOperation(eq(USER_ID), any(), eq(request))).thenReturn(challenge);
        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST)).isInstanceOfSatisfying(
                com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationPendingException.class,
                pending -> assertThat(pending.challengeId()).isEqualTo(challenge));
        var operation = ArgumentCaptor.forClass(com.amateur.howmuchbackend.application.external.model.FinanceOperation.class);
        verify(auth).suspendOperation(eq(USER_ID), operation.capture(), eq(request));
        assertThat(operation.getValue().collectedAt()).isEqualTo(LocalDateTime.of(2026, 9, 13, 3, 0));
        assertThat(operation.getValue().collectedThrough()).isEqualTo(AUGUST.atEndOfMonth());
        verifyNoInteractions(writer);
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
        verify(refreshStorePort).release(ACCOUNT_ID, AUGUST, CLAIM_TOKEN);
    }

    @Test
    void pendingSmsAdmissionDoesNotCallProvider() {
        Account account = account("11");
        var auth = org.mockito.Mockito.mock(com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService.class);
        service.authentication(auth);
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(refreshStorePort.tryClaim(eq(ACCOUNT_ID), eq(AUGUST), any())).thenReturn(Optional.of(CLAIM_TOKEN));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(auth.withTransactionQueryAdmission(eq(USER_ID), eq("0020"), any())).thenThrow(
                new com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationPendingException(java.util.UUID.randomUUID()));
        assertThatThrownBy(() -> service.sync(ACCOUNT_ID, AUGUST)).isInstanceOf(
                com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationPendingException.class);
        verifyNoInteractions(bankTransactionPort, writer);
    }

    @Test
    void smsCompletionWritesCoverageOnlyAfterOuterCommitAndKeepsOriginalCutoff() {
        org.mockito.Mockito.reset(currentActorPort);
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        var at = LocalDateTime.of(2026, 9, 12, 14, 59);
        var cutoff = LocalDate.of(2026, 9, 12);
        var operation = new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                "TRANSACTION_LIST", "0020", account.externalConnectionId(), ACCOUNT_ID, "2026-09", null, false, at, cutoff);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.completeAdditionalAuthentication(USER_ID, operation, List.of());
            verify(writer).persist(eq(USER_ID), eq(ACCOUNT_ID), eq(YearMonth.of(2026, 9)), anyList(), eq(at));
            verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
            verify(refreshStorePort, never()).markFresh(any(), any(), any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(
                    org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(coveragePort).markCovered(ACCOUNT_ID, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1), cutoff, at, Duration.ofDays(30));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void smsCompletionRollbackDoesNotPublishCoverage() {
        org.mockito.Mockito.reset(currentActorPort);
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        var operation = new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                "TRANSACTION_LIST", "0020", account.externalConnectionId(), ACCOUNT_ID, AUGUST.toString(), null, false,
                LocalDateTime.of(2026, 9, 13, 3, 0), AUGUST.atEndOfMonth());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.completeAdditionalAuthentication(USER_ID, operation, List.of());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                    sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
            verify(refreshStorePort, never()).markFresh(any(), any(), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void legacySmsCompletionStoresRowsWithoutInventingCoverage() {
        org.mockito.Mockito.reset(currentActorPort);
        Account account = account("11");
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        service.completeAdditionalAuthentication(USER_ID,
                new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                        "TRANSACTION_LIST", "0020", account.externalConnectionId(), ACCOUNT_ID, AUGUST.toString(), null, false), List.of());
        verify(writer).persist(eq(USER_ID), eq(ACCOUNT_ID), eq(AUGUST), anyList(), any());
        verify(coveragePort, never()).markCovered(any(), any(), any(), any(), any(), any());
    }

    private Account account(String productCode) {
        return new Account(
                ACCOUNT_ID, USER_ID, 7L, "0020", "candidate-1", "1002123456789",
                AccountType.DEPOSIT_TRUST, "생활비 통장", "****6789", new BigDecimal("1250000"),
                "KRW", true, productCode, true, 0
        );
    }
}
