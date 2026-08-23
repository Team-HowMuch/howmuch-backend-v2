package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementReceiptExpenseService implements
        ImportSettlementReceiptExpenseUseCase,
        GetSettlementItemsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final ExtractReceiptItemsPort extractReceiptItemsPort;
    private final SettlementReceiptExpenseWriter receiptExpenseWriter;

    @Override
    public SettlementReceiptExpenseMutationResult importExpense(
            UUID settlementId,
            ImportSettlementReceiptExpenseCommand command
    ) {
        String receiptReference = validateReference(command);
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        settlement.ensureExpensesEditable();

        ExtractReceiptItemsPort.ReceiptExtraction extraction = extractReceiptItemsPort
                .extract(receiptReference)
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
                || normalized.length() > 255) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST
            );
        }
        return normalized;
    }
}
