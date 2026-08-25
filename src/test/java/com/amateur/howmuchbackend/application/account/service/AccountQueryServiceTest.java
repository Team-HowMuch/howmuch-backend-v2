package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadAccountTransactionPort loadTransactionPort;
    @Mock private SaveAccountTransactionPort saveTransactionPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private BankTransactionPort bankTransactionPort;
    @Mock private BankCatalog bankCatalog;

    private AccountQueryService service;

    @BeforeEach
    void setUp() {
        service = new AccountQueryService(
                currentActorPort, loadAccountPort, loadTransactionPort, saveTransactionPort,
                connectionStorePort, bankTransactionPort, bankCatalog
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    @Test
    @DisplayName("금융기관 필터와 기본·최근 사용 정렬을 적용한다")
    void filtersAndSortsAccounts() {
        Account first = account(1L, "0020", true, 1);
        Account second = account(2L, "0020", false, 0);
        Account otherBank = account(3L, "0004", false, 2);
        LocalDateTime recent = LocalDateTime.of(2026, 8, 22, 1, 0);
        when(loadAccountPort.findActiveByUserId(USER_ID)).thenReturn(List.of(second, otherBank, first));
        when(loadTransactionPort.findLastUsedAtByAccountIds(anyList()))
                .thenReturn(Map.of(second.id(), recent));
        when(bankCatalog.getBanks()).thenReturn(List.of(
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
    void returnsCachedSummary() {
        Account account = account(1L, "0020", true, 0);
        AccountMonthlySummary summary = new AccountMonthlySummary(
                10L, account.id(), AUGUST, new BigDecimal("12500.0000"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(loadTransactionPort.findSummary(account.id(), AUGUST)).thenReturn(Optional.of(summary));
        when(bankCatalog.getBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        var result = service.getAccountDetail(account.id(), AUGUST);

        assertThat(result.monthlyExpenseAmount()).isEqualByComparingTo("12500.0000");
        assertThat(result.expenseCount()).isOne();
        verifyNoInteractions(connectionStorePort, bankTransactionPort, saveTransactionPort);
    }

    @Test
    @DisplayName("월 요약이 없으면 CODEF 거래를 중복 Key와 함께 저장하고 요약을 캐시한다")
    void fetchesTransactionsAndCachesSummary() {
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
        when(bankCatalog.getBanks()).thenReturn(List.of(
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
        YearMonth future = YearMonth.now(ZoneId.of("Asia/Seoul")).plusMonths(1);

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
}
