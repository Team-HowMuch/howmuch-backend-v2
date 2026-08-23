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
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        SettlementExpense saved = saveSettlementExpensePort.save(expense);
        return SettlementExpenseMutationResult.of(
                saved,
                loadSettlementExpensePort.totalAmount(settlementId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementExpenseListResult get(UUID settlementId) {
        loadOwnedSettlement(settlementId);
        return SettlementExpenseListResult.from(
                loadSettlementExpensePort.findAllBySettlementId(settlementId)
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
        SettlementExpense saved = saveSettlementExpensePort.update(updated);
        return SettlementExpenseMutationResult.of(
                saved,
                loadSettlementExpensePort.totalAmount(settlementId)
        );
    }

    @Override
    @Transactional
    public SettlementExpenseTotalResult remove(UUID settlementId, UUID expenseId) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureExpensesEditable();
        SettlementExpense expense = loadExpense(settlementId, expenseId);
        expense.ensureRemovable();
        deleteSettlementExpensePort.delete(expense);
        return new SettlementExpenseTotalResult(
                loadSettlementExpensePort.totalAmount(settlementId)
        );
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

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
