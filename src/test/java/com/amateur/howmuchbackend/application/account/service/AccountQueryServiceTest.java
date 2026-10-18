package com.amateur.howmuchbackend.application.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.policy.AccountTransactionDisplayNormalizer;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock private AccountTransactionCoveragePort coveragePort;
    @Mock private com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort refreshStorePort;
    @Mock private BankCatalog bankCatalog;

    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        service = new AccountQueryService(
                currentActorPort, loadAccountPort, loadTransactionPort, coveragePort, refreshStorePort, bankCatalog,
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
                new BankCatalogResult("0020", "우리은행", "woori.png", false),
                new BankCatalogResult("0004", "국민은행", "kb.png", false)
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
    @DisplayName("월 요약이 있으면 저장된 상세를 반환한다")
    void accountSummary_cached_returnsStoredSummary() {
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), AUGUST, new BigDecimal("12500.0000"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.of(summary));
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png", false)
        ));

        var result = service.getAccountDetail(account.id(), AUGUST);

        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500.0000");
        assertThat(result.expenseCount()).isOne();
        assertThat(result.synced()).isFalse();
        assertThat(result.lastSyncedAt()).isNull();
    }

    @Test
    @DisplayName("수집 이력이 있는 월은 synced와 마지막 수집 시각을 반환한다")
    void accountSummary_syncedMonth_reportsLastSyncedAt() {
        Account account = account(1L, "0020", true, 0);
        LocalDateTime syncedAt = LocalDateTime.of(2026, 9, 1, 1, 30);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.empty());
        when(coveragePort.findCoveredAt(account.id(), AUGUST)).thenReturn(Optional.of(syncedAt));
        when(coveragePort.coversMonth(account.id(), AUGUST)).thenReturn(true);
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png", false)
        ));

        when(loadTransactionPort.calculateSummary(account.id(), AUGUST)).thenReturn(
                new AccountMonthlySummary(null, account.id(), AUGUST, BigDecimal.ZERO, 0, null));
        var result = service.getAccountDetail(account.id(), AUGUST);

        assertThat(result.synced()).isTrue();
        assertThat(result.lastSyncedAt()).isEqualTo(syncedAt);
    }

    @Test
    @DisplayName("과거 월 부분 수집은 시각을 유지하지만 완료로 표시하지 않고 진행 중 상태를 반환한다")
    void accountSummary_partialMonth_reportsIncompleteAndInProgress() {
        Account account = account(1L, "0020", true, 0);
        LocalDateTime partialAt = LocalDateTime.of(2026, 8, 20, 0, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(coveragePort.findCoveredAt(account.id(), AUGUST)).thenReturn(Optional.of(partialAt));
        when(refreshStorePort.isSyncInProgress(account.id(), AUGUST)).thenReturn(true);
        when(bankCatalog.getAllBanks()).thenReturn(List.of(new BankCatalogResult("0020", "우리은행", "woori.png", false)));
        when(loadTransactionPort.calculateSummary(account.id(), AUGUST)).thenReturn(
                new AccountMonthlySummary(null, account.id(), AUGUST, BigDecimal.ZERO, 0, null));
        var result = service.getAccountDetail(account.id(), AUGUST);
        assertThat(result.synced()).isFalse();
        assertThat(result.lastSyncedAt()).isEqualTo(partialAt);
        assertThat(result.syncInProgress()).isTrue();
    }

    @Test
    @DisplayName("월 요약과 거래가 모두 없으면 DB 집계로 0원 요약을 반환한다")
    void accountSummary_missing_returnsEmptySummaryWithoutCodef() {
        Account account = account(1L, "0020", true, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.empty());
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png", false)
        ));

        when(loadTransactionPort.calculateSummary(account.id(), AUGUST)).thenReturn(
                new AccountMonthlySummary(null, account.id(), AUGUST, BigDecimal.ZERO, 0, null));
        var result = service.getAccountDetail(account.id(), AUGUST);

        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("0");
        assertThat(result.expenseCount()).isZero();
        assertThat(result.lastUsedAt()).isNull();
    }

    @Test
    @DisplayName("거래내역 조회는 저장된 거래만 반환한다")
    void accountTransactions_returnsStoredRowsOnly() {
        Account account = account(1L, "0020", true, 0);
        Transaction stored = transaction(11L, account.id(), "a".repeat(64), "12000", 20);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findByAccountIdAndYearMonth(account.id(), AUGUST))
                .thenReturn(List.of(stored));
        when(loadTransactionPort.findLinkedSettlements(account.id(), List.of(stored.externalTransactionKey())))
                .thenReturn(List.of());

        var result = service.getAccountTransactions(account.id(), AUGUST);

        assertThat(result).singleElement().satisfies(transaction -> {
            assertThat(transaction.id()).isEqualTo(11L);
            assertThat(transaction.amount()).isEqualByComparingTo("12000");
        });
    }

    @Test
    @DisplayName("거래별로 연결된 여러 정산을 반환하고 미연결 거래는 빈 목록을 반환한다")
    void accountTransactions_linkedSettlements_returnedPerTransaction() {
        Account account = account(1L, "0020", true, 0);
        Transaction linked = transaction(11L, account.id(), "a".repeat(64), "12000", 20);
        Transaction unlinked = transaction(12L, account.id(), "b".repeat(64), "18000", 21);
        UUID firstSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001001");
        UUID secondSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001002");
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID))
                .thenReturn(Optional.of(account));
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
    @DisplayName("타인 또는 비활성 계좌는 없는 계좌와 같은 오류를 반환한다")
    void accountLookup_unknownOrInactive_returnsNotFound() {
        when(loadAccountPort.findActiveByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccountDetail(99L, AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("미래 월은 거절한다")
    void accountTransactions_futureMonth_rejected() {
        Account account = account(1L, "0020", true, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        YearMonth future = SEPTEMBER.plusMonths(1);

        assertThatThrownBy(() -> service.getAccountDetail(account.id(), future))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.INVALID_ACCOUNT_QUERY));
    }

    @Test
    @DisplayName("거래내역을 지원하지 않는 상품은 조회 전에 거절한다")
    void accountTransactions_unsupportedProduct_rejected() {
        Account account = account(1L, "0020", "12", true, 0);
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.getAccountTransactions(account.id(), AUGUST))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED));
    }

    private Account account(Long id, String organization, boolean isDefault, int displayOrder) {
        return account(id, organization, "11", isDefault, displayOrder);
    }

    private Account account(
            Long id,
            String organization,
            String productCode,
            boolean isDefault,
            int displayOrder
    ) {
        return new Account(
                id, USER_ID, 7L, organization, "candidate-" + id, "1002123456789",
                AccountType.DEPOSIT_TRUST, "생활비 통장", "****6789", new BigDecimal("1250000"),
                "KRW", true, productCode, isDefault, displayOrder
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
