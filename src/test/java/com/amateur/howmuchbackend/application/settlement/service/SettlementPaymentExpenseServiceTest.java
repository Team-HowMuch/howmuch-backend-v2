package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementPaymentExpenseCommand;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제내역 정산 지출 Application 흐름")
class SettlementPaymentExpenseServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;
    private static final Long TRANSACTION_ID = 20L;
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 9, 0);
    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 8);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private GetAccountTransactionsUseCase getAccountTransactionsUseCase;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadAccountTransactionPort loadAccountTransactionPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private SaveSettlementExpensePort saveSettlementExpensePort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    private SettlementPaymentExpenseService service;

    @BeforeEach
    void setUp() {
        service = new SettlementPaymentExpenseService(
                currentActorPort,
                getAccountTransactionsUseCase,
                loadAccountPort,
                loadAccountTransactionPort,
                loadSettlementPort,
                loadSettlementExpensePort,
                saveSettlementExpensePort,
                saveSettlementPort,
                allocationDraftInvalidator
        );
        lenient().when(allocationDraftInvalidator.invalidate(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("계좌 동기화 뒤 출금 내역만 후보로 반환하고 이미 가져온 항목을 표시한다")
    void getsExpenseCandidatesWithImportedState() {
        givenOwnedDraft();
        Transaction imported = transaction(
                TRANSACTION_ID,
                "imported-key",
                AccountTransactionType.EXPENSE,
                "12000",
                "점심"
        );
        Transaction available = transaction(
                TRANSACTION_ID + 1,
                "available-key",
                AccountTransactionType.EXPENSE,
                "8000",
                "  카페  "
        );
        Transaction income = transaction(
                TRANSACTION_ID + 2,
                "income-key",
                AccountTransactionType.INCOME,
                "50000",
                "급여"
        );
        when(getAccountTransactionsUseCase.getAccountTransactions(ACCOUNT_ID, TARGET_MONTH))
                .thenReturn(List.of());
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(importedExpense("imported-key")));
        when(loadAccountTransactionPort.findByAccountIdAndYearMonth(ACCOUNT_ID, TARGET_MONTH))
                .thenReturn(List.of(imported, available, income));

        var result = service.get(SETTLEMENT_ID, ACCOUNT_ID, TARGET_MONTH);

        verify(getAccountTransactionsUseCase).getAccountTransactions(ACCOUNT_ID, TARGET_MONTH);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(result.get(0).imported()).isTrue();
        assertThat(result.get(1).description()).isEqualTo("카페");
        assertThat(result.get(1).amount()).isEqualTo(8_000);
        assertThat(result.get(1).imported()).isFalse();
    }

    @Test
    @DisplayName("소유한 계좌의 출금 내역을 잠긴 정산에 저장하고 최신 누적액을 반환한다")
    void importsOwnedExpenseTransaction() {
        givenOwnedDraftForUpdate();
        Transaction transaction = transaction(
                TRANSACTION_ID,
                "transaction-key",
                AccountTransactionType.EXPENSE,
                "42000",
                null
        );
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, CREATOR_ID))
                .thenReturn(Optional.of(account()));
        when(loadAccountTransactionPort.findByAccountIdAndId(ACCOUNT_ID, TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));
        when(loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                SETTLEMENT_ID,
                SettlementExpenseSource.PAYMENT_HISTORY,
                "transaction-key"
        )).thenReturn(false);
        when(loadSettlementExpensePort.nextDisplayOrder(SETTLEMENT_ID)).thenReturn(2);
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(8_000L);
        when(saveSettlementExpensePort.save(any(SettlementExpense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementPaymentExpenseCommand(ACCOUNT_ID, TRANSACTION_ID)
        );

        ArgumentCaptor<SettlementExpense> captor = ArgumentCaptor.forClass(
                SettlementExpense.class
        );
        verify(loadSettlementPort).findByIdForUpdate(SETTLEMENT_ID);
        verify(saveSettlementExpensePort).save(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(
                SettlementExpenseSource.PAYMENT_HISTORY
        );
        assertThat(captor.getValue().sourceReference()).isEqualTo("transaction-key");
        assertThat(captor.getValue().description()).isEqualTo("결제내역");
        assertThat(captor.getValue().displayOrder()).isEqualTo(2);
        assertThat(result.totalExpenseAmount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("같은 결제내역을 이미 가져왔으면 저장하지 않는다")
    void rejectsDuplicateImport() {
        givenOwnedDraftForUpdate();
        Transaction transaction = transaction(
                TRANSACTION_ID,
                "transaction-key",
                AccountTransactionType.EXPENSE,
                "42000",
                "저녁 식사"
        );
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, CREATOR_ID))
                .thenReturn(Optional.of(account()));
        when(loadAccountTransactionPort.findByAccountIdAndId(ACCOUNT_ID, TRANSACTION_ID))
                .thenReturn(Optional.of(transaction));
        when(loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                SETTLEMENT_ID,
                SettlementExpenseSource.PAYMENT_HISTORY,
                "transaction-key"
        )).thenReturn(true);

        assertThatThrownBy(() -> service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementPaymentExpenseCommand(ACCOUNT_ID, TRANSACTION_ID)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_ALREADY_IMPORTED
                ));
        verify(saveSettlementExpensePort, never()).save(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("입금 내역과 원 단위로 표현할 수 없는 내역은 가져오지 않는다")
    void rejectsNonImportableTransaction() {
        givenOwnedDraftForUpdate();
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, CREATOR_ID))
                .thenReturn(Optional.of(account()));
        when(loadAccountTransactionPort.findByAccountIdAndId(ACCOUNT_ID, TRANSACTION_ID))
                .thenReturn(Optional.of(transaction(
                        TRANSACTION_ID,
                        "income-key",
                        AccountTransactionType.INCOME,
                        "50000",
                        "입금"
                )));

        assertThatThrownBy(() -> service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementPaymentExpenseCommand(ACCOUNT_ID, TRANSACTION_ID)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE
                ));
        verify(saveSettlementExpensePort, never()).save(any(SettlementExpense.class));
    }

    private void givenOwnedDraft() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
    }

    private void givenOwnedDraftForUpdate() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(draft()));
    }

    private Settlement draft() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                50_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Account account() {
        return new Account(
                ACCOUNT_ID,
                CREATOR_ID,
                1L,
                "0004",
                "candidate",
                "account-key",
                AccountType.DEPOSIT_TRUST,
                "생활비",
                "1234",
                BigDecimal.valueOf(100_000),
                "KRW",
                true,
                "product",
                true,
                0
        );
    }

    private Transaction transaction(
            Long id,
            String externalKey,
            AccountTransactionType type,
            String amount,
            String description
    ) {
        return new Transaction(
                id,
                ACCOUNT_ID,
                externalKey,
                type,
                new BigDecimal(amount),
                CREATED_AT,
                description,
                BigDecimal.ZERO
        );
    }

    private SettlementExpense importedExpense(String sourceReference) {
        return SettlementExpense.paymentHistory(
                SETTLEMENT_ID,
                sourceReference,
                "점심",
                12_000,
                0,
                CREATED_AT
        );
    }
}
