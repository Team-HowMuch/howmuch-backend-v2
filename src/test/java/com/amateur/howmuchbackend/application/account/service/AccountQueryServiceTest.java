package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.policy.AccountTransactionDisplayNormalizer;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 조회 Application 흐름")
class AccountQueryServiceTest {
    private static final Long USER_ID = 1L;
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-13T03:00:00Z"), ZoneOffset.UTC);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadAccountTransactionPort loadTransactionPort;
    @Mock private SaveAccountTransactionPort saveTransactionPort;
    @Mock private AccountTransactionRefreshStorePort refreshStorePort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private BankTransactionPort bankTransactionPort;
    @Mock private BankCatalog bankCatalog;

    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        service = new AccountQueryService(
                currentActorPort, loadAccountPort, loadTransactionPort, saveTransactionPort, refreshStorePort,
                connectionStorePort, bankTransactionPort, bankCatalog,
                new AccountTransactionDisplayNormalizer(), CLOCK
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    @Test
    @DisplayName("금융기관 필터와 기본·최근 사용 정렬을 적용한다")
    void accountList_institutionFilterAndSort_applied() {
        Account first = account(1L, "0020", true, 1);
        Account second = account(2L, "0020", false, 0);
        Account otherBank = account(3L, "0004", false, 2);
        LocalDateTime recent = LocalDateTime.of(2026, 8, 22, 1, 0);
        when(loadAccountPort.findActiveByUserId(USER_ID)).thenReturn(List.of(second, otherBank, first));
        when(loadTransactionPort.findLastUsedAtByAccountIds(anyList()))
                .thenReturn(Map.of(second.id(), recent));
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png"),
                new BankCatalogResult("0004", "국민은행", "kb.png")
        ));

        var defaults = service.getAccounts(new GetAccountsQuery("0020", AccountSort.DEFAULT));
        var recents = service.getAccounts(new GetAccountsQuery("0020", AccountSort.RECENT));

        assertThat(defaults).extracting(result -> result.id()).containsExactly(1L, 2L);
        assertThat(recents).extracting(result -> result.id()).containsExactly(2L, 1L);
        assertThat(defaults).allSatisfy(result -> {
            assertThat(result.organization()).isEqualTo("0020");
            assertThat(result.institutionName()).isEqualTo("우리은행");
        });
    }

    @Test
    @DisplayName("월 요약이 있으면 CODEF를 호출하지 않고 상세를 반환한다")
    void accountSummary_cached_doesNotCallCodef() {
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), AUGUST, new BigDecimal("12500.0000"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.of(summary));
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        var result = service.getAccountDetail(account.id(), AUGUST);

        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500.0000");
        assertThat(result.expenseCount()).isOne();
        verifyNoInteractions(connectionStorePort, bankTransactionPort, saveTransactionPort);
    }

    @Test
    @DisplayName("월 요약이 없으면 CODEF 거래를 중복 Key와 함께 저장하고 요약을 캐시한다")
    void accountTransactions_notCached_savedByKeyAndSummaryCached() {
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary calculated = new AccountMonthlySummary(
                null, account.id(), AUGUST, new BigDecimal("12500"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST))
                .thenReturn(Optional.empty(), Optional.of(calculated));
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(account.id(), USER_ID))
                .thenReturn(Optional.of(account));
        when(bankTransactionPort.fetch("CID-1", "0020", "1002123456789", AUGUST.atDay(1), AUGUST.atEndOfMonth()))
                .thenReturn(List.of(new BankTransactionPort.BankTransaction(
                        AccountTransactionType.EXPENSE,
                        new BigDecimal("12500"),
                        LocalDateTime.of(2026, 8, 20, 0, 15),
                        "편의점 생활비",
                        new BigDecimal("1250000")
                )));
        when(loadTransactionPort.calculateSummary(account.id(), AUGUST)).thenReturn(calculated);
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        service.getAccountDetail(account.id(), AUGUST);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveTransactionPort).saveAllIgnoringDuplicates(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(transaction -> {
            assertThat(transaction.externalTransactionKey()).matches("[0-9a-f]{64}");
            assertThat(transaction.type()).isEqualTo(AccountTransactionType.EXPENSE);
            assertThat(transaction.amount()).isEqualByComparingTo("12500");
        });
        verify(saveTransactionPort).saveSummaryIfAbsent(calculated);
    }

    @Test
    @DisplayName("현재 월 refresh marker가 유효하면 CODEF를 호출하지 않는다")
    void returnsCurrentMonthSummaryWhenRefreshMarkerIsFresh() {
        YearMonth currentMonth = SEPTEMBER;
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), currentMonth, new BigDecimal("12500.0000"), 1,
                LocalDateTime.now()
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), currentMonth)).thenReturn(Optional.of(summary));
        when(refreshStorePort.isFresh(account.id(), currentMonth)).thenReturn(true);
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        var result = service.getAccountDetail(account.id(), currentMonth);

        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500.0000");
        verifyNoInteractions(connectionStorePort, bankTransactionPort, saveTransactionPort);
    }

    @Test
    @DisplayName("현재 월 refresh marker가 만료되면 CODEF를 다시 조회한다")
    void refreshesCurrentMonthWhenRefreshMarkerIsExpired() {
        YearMonth currentMonth = SEPTEMBER;
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), currentMonth, new BigDecimal("12500.0000"), 1,
                LocalDateTime.now()
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), currentMonth))
                .thenReturn(Optional.of(summary), Optional.of(summary));
        when(refreshStorePort.isFresh(account.id(), currentMonth)).thenReturn(false);
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(account.id(), USER_ID))
                .thenReturn(Optional.of(account));
        when(bankTransactionPort.fetch(
                "CID-1", "0020", account.externalAccountKey(),
                currentMonth.atDay(1), TODAY
        )).thenReturn(List.of(new BankTransactionPort.BankTransaction(
                AccountTransactionType.EXPENSE,
                new BigDecimal("12500"),
                LocalDateTime.now(),
                "테스트 거래",
                new BigDecimal("1000000")
        )));
        when(loadTransactionPort.calculateSummary(account.id(), currentMonth)).thenReturn(summary);
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.getAccountDetail(account.id(), currentMonth);

            verify(bankTransactionPort).fetch(
                    "CID-1", "0020", account.externalAccountKey(),
                    currentMonth.atDay(1), TODAY
            );
            verify(refreshStorePort, never()).markFresh(
                    account.id(), currentMonth, java.time.Duration.ofMinutes(1)
            );

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(refreshStorePort).markFresh(account.id(), currentMonth, java.time.Duration.ofMinutes(1));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("거래별로 연결된 여러 정산을 반환하고 미연결 거래는 빈 목록을 반환한다")
    void returnsLinkedSettlementsWithTransactions() {
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), AUGUST, new BigDecimal("30000"), 2,
                LocalDateTime.of(2026, 8, 21, 0, 15)
        );
        Transaction linked = transaction(11L, account.id(), "a".repeat(64), "12000", 20);
        Transaction unlinked = transaction(12L, account.id(), "b".repeat(64), "18000", 21);
        UUID firstSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001001");
        UUID secondSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001002");
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID))
                .thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.of(summary));
        when(loadTransactionPort.findByAccountIdAndYearMonth(account.id(), AUGUST))
                .thenReturn(List.of(unlinked, linked));
        when(loadTransactionPort.findLinkedSettlements(
                account.id(),
                List.of(unlinked.externalTransactionKey(), linked.externalTransactionKey())
        )).thenReturn(List.of(
                new LoadAccountTransactionPort.LinkedSettlement(
                        linked.externalTransactionKey(),
                        firstSettlementId,
                        "점심 정산",
                        SettlementStatus.AWAITING_DEPOSIT
                ),
                new LoadAccountTransactionPort.LinkedSettlement(
                        linked.externalTransactionKey(),
                        secondSettlementId,
                        "여행 정산",
                        SettlementStatus.COMPLETED
                )
        ));

        var result = service.getAccountTransactions(account.id(), AUGUST);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().settlements()).isEmpty();
        assertThat(result.getFirst().display().title()).isEqualTo("테스트");
        assertThat(result.getFirst().display().kind())
                .isEqualTo(com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult.Display.Kind.OTHER);
        assertThat(result.getLast().settlements())
                .extracting(settlement -> settlement.settlementId())
                .containsExactly(firstSettlementId, secondSettlementId);
    }

    @Test
    @DisplayName("연결 잠금 전에 계좌가 삭제되면 CODEF 조회 없이 404로 종료한다")
    void rejectsAccountDeletedBeforeTransactionFetch() {
        Account account = account(1L, "0020", true, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.empty());
        when(connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(account.externalConnectionId(), USER_ID, "CID-1")));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(account.id(), USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccountDetail(account.id(), AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(bankTransactionPort, saveTransactionPort);
    }

    @Test
    @DisplayName("타인 또는 비활성 계좌는 없는 계좌와 같은 오류를 반환한다")
    void rejectsUnknownOwnedAccount() {
        when(loadAccountPort.findActiveByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccountDetail(99L, AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));
        verify(bankTransactionPort, never()).fetch(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("미래 월은 외부 조회 전에 거절한다")
    void rejectsFutureMonth() {
        Account account = account(1L, "0020", true, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        YearMonth future = SEPTEMBER.plusMonths(1);

        assertThatThrownBy(() -> service.getAccountDetail(account.id(), future))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.INVALID_ACCOUNT_QUERY));
        verifyNoInteractions(bankTransactionPort, saveTransactionPort);
    }

    private Account account(Long id, String organization, boolean isDefault, int displayOrder) {
        return new Account(
                id, USER_ID, 7L, organization, "candidate-" + id, "1002123456789",
                AccountType.DEPOSIT_TRUST, "생활비 통장", "****6789", new BigDecimal("1250000"),
                "KRW", true, "11", isDefault, displayOrder
        );
    }

    private Transaction transaction(
            Long id,
            Long accountId,
            String externalTransactionKey,
            String amount,
            int day
    ) {
        return new Transaction(
                id,
                accountId,
                externalTransactionKey,
                AccountTransactionType.EXPENSE,
                new BigDecimal(amount),
                LocalDateTime.of(2026, 8, day, 0, 15),
                "테스트 거래",
                new BigDecimal("1000000")
        );
    }
}
