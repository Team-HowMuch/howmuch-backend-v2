package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementExpensesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseListResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseTotalResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementExpenseService implements
        CreateSettlementExpenseUseCase,
        GetSettlementExpensesUseCase,
        UpdateSettlementExpenseUseCase,
        RemoveSettlementExpenseUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final SaveSettlementExpensePort saveSettlementExpensePort;
    private final DeleteSettlementExpensePort deleteSettlementExpensePort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    @Override
    @Transactional
    public SettlementExpenseMutationResult create(
            UUID settlementId,
            CreateSettlementExpenseCommand command
    ) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureExpensesEditable();
        SettlementExpense expense = SettlementExpense.manual(
                settlementId,
                command.description(),
                command.amount(),
                loadSettlementExpensePort.nextDisplayOrder(settlementId),
                now()
        );
        long totalExpenseAmount = addToTotal(
                loadSettlementExpensePort.totalAmount(settlementId),
                expense.amount()
        );
        invalidateAllocation(settlement);
        SettlementExpense saved = saveSettlementExpensePort.save(expense);
        return SettlementExpenseMutationResult.of(
                saved,
                totalExpenseAmount
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementExpenseListResult get(UUID settlementId) {
        loadOwnedSettlement(settlementId);
        List<SettlementExpense> expenses = loadSettlementExpensePort
                .findAllBySettlementId(settlementId);
        return SettlementExpenseListResult.from(
                expenses,
                calculateTotal(expenses)
        );
    }

    @Override
    @Transactional
    public SettlementExpenseMutationResult update(
            UUID settlementId,
            UUID expenseId,
            UpdateSettlementExpenseCommand command
    ) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureExpensesEditable();
        SettlementExpense expense = loadExpense(settlementId, expenseId);
        SettlementExpense updated = expense.updateManual(
                command.description(),
                command.amount(),
                now()
        );
        long totalExpenseAmount = replaceInTotal(
                loadSettlementExpensePort.totalAmount(settlementId),
                expense.amount(),
                updated.amount()
        );
        invalidateAllocation(settlement);
        SettlementExpense saved = saveSettlementExpensePort.update(updated);
        return SettlementExpenseMutationResult.of(
                saved,
                totalExpenseAmount
        );
    }

    @Override
    @Transactional
    public SettlementExpenseTotalResult remove(UUID settlementId, UUID expenseId) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureExpensesEditable();
        SettlementExpense expense = loadExpense(settlementId, expenseId);
        long totalExpenseAmount = removeFromTotal(
                loadSettlementExpensePort.totalAmount(settlementId),
                expense.amount()
        );
        invalidateAllocation(settlement);
        deleteSettlementExpensePort.delete(expense);
        return new SettlementExpenseTotalResult(totalExpenseAmount);
    }

    private Settlement loadOwnedSettlement(UUID settlementId) {
        return verifyOwnership(loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )));
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId) {
        return verifyOwnership(loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )));
    }

    private Settlement verifyOwnership(Settlement settlement) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        if (!settlement.isCreatedBy(actor.userId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private SettlementExpense loadExpense(UUID settlementId, UUID expenseId) {
        return loadSettlementExpensePort.findBySettlementIdAndId(settlementId, expenseId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_FOUND
                ));
    }

    private long calculateTotal(List<SettlementExpense> expenses) {
        long total = 0L;
        for (SettlementExpense expense : expenses) {
            total = addToTotal(total, expense.amount());
        }
        return total;
    }

    private long addToTotal(long currentTotal, long amount) {
        try {
            return Math.addExact(currentTotal, amount);
        } catch (ArithmeticException exception) {
            throw invalidExpenseTotal(exception);
        }
    }

    private long replaceInTotal(long currentTotal, long previousAmount, long newAmount) {
        return addToTotal(removeFromTotal(currentTotal, previousAmount), newAmount);
    }

    private long removeFromTotal(long currentTotal, long amount) {
        long remaining;
        try {
            remaining = Math.subtractExact(currentTotal, amount);
        } catch (ArithmeticException exception) {
            throw invalidExpenseTotal(exception);
        }
        if (remaining < 0) {
            throw invalidExpenseTotal(null);
        }
        return remaining;
    }

    private ApplicationException invalidExpenseTotal(Throwable cause) {
        return cause == null
                ? ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL)
                : ApplicationException.of(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                        cause
                );
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private void invalidateAllocation(Settlement settlement) {
        Settlement invalidated = allocationDraftInvalidator.invalidate(settlement, now());
        if (invalidated != settlement) {
            saveSettlementPort.update(invalidated);
        }
    }
}
