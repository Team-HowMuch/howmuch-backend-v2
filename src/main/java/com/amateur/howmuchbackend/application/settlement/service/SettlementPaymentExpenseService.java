package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementPaymentExpenseCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementPaymentExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementPaymentExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementPaymentExpenseCandidateResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementPaymentExpenseService implements
        GetSettlementPaymentExpenseCandidatesUseCase,
        ImportSettlementPaymentExpenseUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String DEFAULT_DESCRIPTION = "결제내역";

    private final CurrentActorPort currentActorPort;
    private final GetAccountTransactionsUseCase getAccountTransactionsUseCase;
    private final LoadAccountPort loadAccountPort;
    private final LoadAccountTransactionPort loadAccountTransactionPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final SaveSettlementExpensePort saveSettlementExpensePort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    @Override
    @Transactional
    public List<SettlementPaymentExpenseCandidateResult> get(
            UUID settlementId,
            Long accountId,
            YearMonth yearMonth
    ) {
        validateAccountId(accountId);
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureExpensesEditable();
        YearMonth targetMonth = yearMonth == null ? YearMonth.now(SEOUL) : yearMonth;
        getAccountTransactionsUseCase.getAccountTransactions(accountId, targetMonth);

        Set<String> importedReferences = loadSettlementExpensePort
                .findAllBySettlementId(settlement.id())
                .stream()
                .filter(expense -> expense.source() == SettlementExpenseSource.PAYMENT_HISTORY)
                .map(SettlementExpense::sourceReference)
                .collect(Collectors.toSet());
        return loadAccountTransactionPort.findByAccountIdAndYearMonth(accountId, targetMonth)
                .stream()
                .filter(transaction -> transaction.type() == AccountTransactionType.EXPENSE)
                .map(transaction -> toCandidate(
                        transaction,
                        importedReferences.contains(transaction.externalTransactionKey())
                ))
                .toList();
    }

    @Override
    @Transactional
    public SettlementExpenseMutationResult importExpense(
            UUID settlementId,
            ImportSettlementPaymentExpenseCommand command
    ) {
        validateCommand(command);
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        settlement.ensureExpensesEditable();
        loadAccountPort.findActiveByIdAndUserId(command.accountId(), actor.userId())
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        Transaction transaction = loadAccountTransactionPort
                .findByAccountIdAndId(command.accountId(), command.transactionId())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PAYMENT_TRANSACTION_NOT_FOUND
                ));
        ensureImportable(transaction);
        if (loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                settlement.id(),
                SettlementExpenseSource.PAYMENT_HISTORY,
                transaction.externalTransactionKey()
        )) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_EXPENSE_ALREADY_IMPORTED
            );
        }

        SettlementExpense expense = SettlementExpense.paymentHistory(
                settlement.id(),
                transaction.externalTransactionKey(),
                description(transaction.description()),
                wonAmount(transaction),
                loadSettlementExpensePort.nextDisplayOrder(settlement.id()),
                now()
        );
        long totalExpenseAmount = addToTotal(
                loadSettlementExpensePort.totalAmount(settlement.id()),
                expense.amount()
        );
        invalidateAllocation(settlement);
        SettlementExpense saved = saveSettlementExpensePort.save(expense);
        return SettlementExpenseMutationResult.of(saved, totalExpenseAmount);
    }

    private SettlementPaymentExpenseCandidateResult toCandidate(
            Transaction transaction,
            boolean imported
    ) {
        return new SettlementPaymentExpenseCandidateResult(
                transaction.id(),
                description(transaction.description()),
                wonAmount(transaction),
                transaction.transactedAt(),
                imported
        );
    }

    private Settlement loadOwnedSettlement(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        return verifyOwnership(loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )), actor.userId());
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId, Long userId) {
        return verifyOwnership(loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )), userId);
    }

    private Settlement verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private void ensureImportable(Transaction transaction) {
        if (transaction.type() != AccountTransactionType.EXPENSE) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE
            );
        }
        wonAmount(transaction);
    }

    private long wonAmount(Transaction transaction) {
        try {
            long amount = transaction.amount().longValueExact();
            if (amount <= 0) {
                throw new ArithmeticException("결제내역 금액은 양수여야 합니다.");
            }
            return amount;
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE,
                    exception
            );
        }
    }

    private String description(String description) {
        if (description == null || description.isBlank()) {
            return DEFAULT_DESCRIPTION;
        }
        return description.strip();
    }

    private void validateCommand(ImportSettlementPaymentExpenseCommand command) {
        if (command == null
                || command.transactionId() == null
                || command.transactionId() <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_PAYMENT_IMPORT_REQUEST
            );
        }
        validateAccountId(command.accountId());
    }

    private void validateAccountId(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_PAYMENT_IMPORT_REQUEST
            );
        }
    }

    private long addToTotal(long currentTotal, long amount) {
        try {
            return Math.addExact(currentTotal, amount);
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                    exception
            );
        }
    }

    private void invalidateAllocation(Settlement settlement) {
        Settlement invalidated = allocationDraftInvalidator.invalidate(settlement, now());
        if (invalidated != settlement) {
            saveSettlementPort.update(invalidated);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
