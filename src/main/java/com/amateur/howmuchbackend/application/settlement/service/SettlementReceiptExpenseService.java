package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementReceiptExpenseService implements
        CreateSettlementReceiptExpenseUseCase,
        ImportSettlementReceiptExpenseUseCase,
        GetSettlementItemsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final ExtractReceiptItemsPort extractReceiptItemsPort;
    private final SettlementReceiptExpenseWriter receiptExpenseWriter;

    @Override
    public SettlementReceiptExpenseMutationResult create(
            UUID settlementId,
            CreateSettlementReceiptExpenseCommand command
    ) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        SubmittedReceipt receipt = validateSubmittedReceipt(command);
        return receiptExpenseWriter.write(
                settlementId,
                actor.userId(),
                receipt.receiptReference(),
                receipt.paidAt(),
                receipt.extraction()
        );
    }

    @Override
    public SettlementReceiptExpenseMutationResult importExpense(
            UUID settlementId,
            ImportSettlementReceiptExpenseCommand command
    ) {
        String receiptReference = validateReference(command);
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        settlement.ensureExpensesEditable();
        ensureNotImported(settlement.id(), receiptReference);

        ExtractReceiptItemsPort.ReceiptExtraction extraction = extractReceiptItemsPort
                .extract(actor.userId(), receiptReference)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_NOT_FOUND
                ));
        return receiptExpenseWriter.write(
                settlement.id(),
                actor.userId(),
                receiptReference,
                extraction
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementItemListResult get(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        try {
            return SettlementItemListResult.from(
                    loadSettlementItemPort.findAllBySettlementId(settlement.id())
            );
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_TOTAL_MISMATCH,
                    exception
            );
        }
    }

    private Settlement loadOwnedSettlement(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private String validateReference(ImportSettlementReceiptExpenseCommand command) {
        if (command == null || command.receiptReference() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST
            );
        }
        String normalized = command.receiptReference().strip();
        if (normalized.isBlank()
                || normalized.length() > SettlementExpense.MAX_SOURCE_REFERENCE_LENGTH) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST
            );
        }
        return normalized;
    }

    private SubmittedReceipt validateSubmittedReceipt(
            CreateSettlementReceiptExpenseCommand command
    ) {
        if (command == null
                || command.receiptReference() == null
                || command.storeName() == null
                || command.items() == null
                || command.items().isEmpty()) {
            throw invalidImportRequest();
        }
        String receiptReference = command.receiptReference().strip();
        String storeName = command.storeName().strip();
        if (receiptReference.isBlank()
                || receiptReference.length() > SettlementExpense.MAX_SOURCE_REFERENCE_LENGTH
                || storeName.isBlank()
                || storeName.length() > SettlementExpense.MAX_DESCRIPTION_LENGTH) {
            throw invalidImportRequest();
        }

        long totalAmount = 0L;
        List<ExtractReceiptItemsPort.ReceiptItem> items = new ArrayList<>(command.items().size());
        try {
            for (CreateSettlementReceiptExpenseCommand.Item item : command.items()) {
                if (item == null || item.name() == null) {
                    throw invalidImportRequest();
                }
                String name = item.name().strip();
                if (name.isBlank()
                        || name.length() > SettlementExpense.MAX_DESCRIPTION_LENGTH
                        || item.quantity() <= 0
                        || item.amount() <= 0) {
                    throw invalidImportRequest();
                }
                totalAmount = Math.addExact(totalAmount, item.amount());
                items.add(new ExtractReceiptItemsPort.ReceiptItem(
                        name,
                        item.quantity(),
                        item.amount()
                ));
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST,
                    exception
            );
        }
        return new SubmittedReceipt(
                receiptReference,
                command.paidAt(),
                new ExtractReceiptItemsPort.ReceiptExtraction(
                        storeName,
                        totalAmount,
                        List.copyOf(items)
                )
        );
    }

    private ApplicationException invalidImportRequest() {
        return ApplicationException.of(
                SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST
        );
    }

    private void ensureNotImported(UUID settlementId, String receiptReference) {
        if (loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                settlementId,
                SettlementExpenseSource.RECEIPT_OCR,
                receiptReference
        )) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_RECEIPT_ALREADY_IMPORTED
            );
        }
    }

    private record SubmittedReceipt(
            String receiptReference,
            LocalDateTime paidAt,
            ExtractReceiptItemsPort.ReceiptExtraction extraction
    ) {
    }
}
