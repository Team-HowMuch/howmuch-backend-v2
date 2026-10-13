package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ReplaceSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementReceiptReplacementService implements ReplaceSettlementReceiptExpenseUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadExpensePort;
    private final SaveSettlementExpensePort saveExpensePort;
    private final SaveSettlementItemPort saveItemPort;
    private final SaveSettlementPort saveSettlementPort;
    private final DeleteSettlementSharePort deleteSharePort;
    private final SettlementItemAllocationStorePort itemAllocationStorePort;
    private final SettlementRevisionGuard revisionGuard;

    @Override
    @Transactional
    public SettlementReceiptExpenseMutationResult replace(
            UUID settlementId, UUID expenseId, ReplaceSettlementReceiptExpenseCommand command
    ) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        var settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        settlement.ensureReceiptReplaceable();
        revisionGuard.ensureUnpaid(settlementId);
        var expense = loadExpensePort.findBySettlementIdAndId(settlementId, expenseId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_FOUND));
        if (expense.source() != SettlementExpenseSource.RECEIPT_OCR) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_RECEIPT_REQUIRED);
        }
        if (command == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST);
        }
        var receipt = SettlementReceiptExpenseService.validateSubmittedReceipt(
                new CreateSettlementReceiptExpenseCommand(expense.sourceReference(), command.storeName(),
                        command.paidAt(), command.items(), command.discount(), command.deliveryFee()));
        var extraction = receipt.extraction();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var updatedExpense = expense.replaceReceipt(extraction.description(), extraction.totalAmount(),
                receipt.paidAt(), extraction.discount(), extraction.deliveryFee(), now);
        long total;
        try {
            total = Math.addExact(Math.subtractExact(loadExpensePort.totalAmount(settlementId),
                    expense.amount()), updatedExpense.amount());
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL, exception);
        }
        var updatedSettlement = settlement.replaceReceiptTotal(total, now);
        var items = SettlementReceiptExpenseWriter.toItems(settlementId, expenseId, extraction.items(), now);
        var savedExpense = saveExpensePort.update(updatedExpense);
        var savedItems = saveItemPort.replaceByExpenseId(settlementId, expenseId, items);
        itemAllocationStorePort.deleteAllSubmissions(settlementId);
        deleteSharePort.deleteAllBySettlementId(settlementId);
        saveSettlementPort.update(updatedSettlement);
        return SettlementReceiptExpenseMutationResult.of(savedExpense, savedItems, total);
    }
}
