package com.amateur.howmuchbackend.application.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 은행 거래 조회 수집 재사용")
class SettlementBankTransactionFetchServiceTest {
    private static final long ACCOUNT_ID = 7L;
    private static final String ORGANIZATION = "0020";
    private static final String ACCOUNT_NUMBER = "1002123456789";
    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final YearMonth JUNE = YearMonth.of(2026, 6);
    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-13T03:00:00Z"), ZoneOffset.UTC);

    @Mock private BankTransactionPort bankTransactionPort;
    @Mock private BankCatalog bankCatalog;
    @Mock private AccountCredentialProtectionService credentialProtection;
    @Mock private SettlementBankLookupBudgetPort budget;
    @Mock private AccountTransactionCoveragePort coverage;
    @Mock private LoadAccountTransactionPort loadTransactionPort;

    private SettlementBankTransactionFetchService service;
    private ExternalConnection connection;

    @BeforeEach
    void setUp() {
        service = new SettlementBankTransactionFetchService(
                bankTransactionPort, bankCatalog, credentialProtection, budget, coverage,
                loadTransactionPort, CLOCK
        );
        connection = new ExternalConnection(3L, 1L, "CID-1");
    }

    @Test
    void widgetReusesRecentCurrentMonthSnapshotWithoutAnotherBankCall() {
        when(coverage.recentlyCovers(eq(ACCOUNT_ID), eq(SEPTEMBER), any(), any(), any(), any())).thenReturn(true);
        when(loadTransactionPort.findByAccountIdAndYearMonth(ACCOUNT_ID, SEPTEMBER)).thenReturn(List.of());
        assertThat(service.fetchForWidget(connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 13, 0, 0), () -> {})).isEmpty();
        verify(bankTransactionPort, never()).fetch(any(), any(), any(), any(), any(), any());
        verify(budget, never()).reserve(anyLong(), eq(false), any(), anyInt(), anyInt());
    }

    @Test
    void widgetExpiredOrMissingSnapshotFallsBackToBank() {
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        service.fetchForWidget(connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 13, 0, 0), () -> {});
        verify(bankTransactionPort).fetch(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 수집한 과거 월은 CODEF 없이 저장 거래를 재사용한다")
    void fetch_coveredPastMonths_usesStoredTransactions() {
        when(coverage.coversMonth(ACCOUNT_ID, MAY))
                .thenReturn(true);
        when(coverage.coversMonth(ACCOUNT_ID, JUNE))
                .thenReturn(true);
        when(coverage.coversMonth(ACCOUNT_ID, JULY))
                .thenReturn(true);
        when(loadTransactionPort.findByAccountIdAndYearMonth(ACCOUNT_ID, MAY))
                .thenReturn(List.of(transaction(1L, MAY)));
        when(loadTransactionPort.findByAccountIdAndYearMonth(ACCOUNT_ID, JUNE))
                .thenReturn(List.of());
        when(loadTransactionPort.findByAccountIdAndYearMonth(ACCOUNT_ID, JULY))
                .thenReturn(List.of(transaction(2L, JULY)));
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new BankTransactionPort.BankTransaction(
                        AccountTransactionType.INCOME,
                        new BigDecimal("1000"),
                        LocalDateTime.of(2026, 8, 5, 0, 0),
                        "입금",
                        new BigDecimal("1000")
                )));

        List<Transaction> result = service.fetch(
                connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                LocalDateTime.of(2026, 5, 10, 0, 0), LocalDateTime.of(2026, 9, 13, 0, 0)
        );

        verify(bankTransactionPort, times(2)).fetch(any(), any(), any(), any(), any(), any());
        verify(loadTransactionPort).findByAccountIdAndYearMonth(ACCOUNT_ID, MAY);
        verify(loadTransactionPort).findByAccountIdAndYearMonth(ACCOUNT_ID, JUNE);
        verify(loadTransactionPort).findByAccountIdAndYearMonth(ACCOUNT_ID, JULY);
        verify(budget, times(2)).reserve(anyLong(), eq(false), any(), anyInt(), anyInt());
        assertThat(result).hasSize(3);
        assertThat(result).noneMatch(item -> item.transactedAt().isBefore(LocalDateTime.of(2026, 5, 10, 0, 0)));
    }

    @Test
    @DisplayName("수집 표식이 없는 과거 월은 조회만 하고 저장 전 표식을 남기지 않는다")
    void fetch_uncoveredPastMonth_callsCodefWithoutCoverage() {
        when(coverage.coversMonth(ACCOUNT_ID, JULY)).thenReturn(false);
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.fetch(
                connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 31, 0, 0)
        );

        verify(bankTransactionPort).fetch(any(), any(), any(), any(), any(), any());
        verify(coverage, never()).markCovered(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("현재 월은 always revalidate 구간이라 다시 조회한다")
    void fetch_currentMonth_alwaysFetches() {
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.fetch(
                connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 13, 0, 0)
        );

        verify(bankTransactionPort).fetch(any(), any(), any(), any(), any(), any());
        verify(coverage, never()).markCovered(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("부분 기간을 반복 조회해도 월 전체 수집으로 승격하지 않는다")
    void partialPeriod_neverMarksWholeMonth() {
        when(bankTransactionPort.fetch(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        for (int i = 0; i < 2; i++) {
            service.fetch(connection, ACCOUNT_ID, ORGANIZATION, ACCOUNT_NUMBER,
                    LocalDateTime.of(2026, 7, 20, 0, 0), LocalDateTime.of(2026, 7, 31, 0, 0));
        }
        verify(bankTransactionPort, times(2)).fetch(any(), any(), any(), any(), any(), any());
        verify(coverage, never()).markCovered(any(), any(), any(), any(), any(), any());
    }

    private Transaction transaction(Long id, YearMonth month) {
        return new Transaction(
                id,
                ACCOUNT_ID,
                "%064d".formatted(id),
                AccountTransactionType.INCOME,
                new BigDecimal("5000"),
                month.atDay(3).atStartOfDay(),
                "저장된 거래",
                new BigDecimal("10000")
        );
    }
}
