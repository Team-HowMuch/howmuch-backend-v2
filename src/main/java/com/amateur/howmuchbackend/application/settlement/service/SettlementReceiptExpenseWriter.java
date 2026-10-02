package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementReceiptExpenseWriter {
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final SaveSettlementExpensePort saveSettlementExpensePort;
    private final SaveSettlementItemPort saveSettlementItemPort;
    private final SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    @Transactional
    public SettlementReceiptExpenseMutationResult write(
            UUID settlementId,
            Long actorId,
            String receiptReference,
            ExtractReceiptItemsPort.ReceiptExtraction extraction
    ) {
        return write(settlementId, actorId, receiptReference, null, extraction);
    }

    @Transactional
    public SettlementReceiptExpenseMutationResult write(
            UUID settlementId,
            Long actorId,
            String receiptReference,
            LocalDateTime paidAt,
            ExtractReceiptItemsPort.ReceiptExtraction extraction
    ) {
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        settlement.ensureExpensesEditable();
        ensureNotImported(settlement.id(), receiptReference);
        ValidatedExtraction validated = validate(extraction);

        LocalDateTime now = now();
        SettlementExpense expense = SettlementExpense.receiptOcr(
                settlement.id(),
                receiptReference,
                validated.description(),
                validated.totalAmount(),
                paidAt,
                loadSettlementExpensePort.nextDisplayOrder(settlement.id()),
                now,
                validated.discount(),
                validated.deliveryFee()
        );
        long totalExpenseAmount = addToTotal(
                loadSettlementExpensePort.totalAmount(settlement.id()),
                expense.amount()
        );
        List<SettlementItem> items = toItems(
                settlement.id(),
                expense.id(),
                validated.items(),
                now
        );

        invalidateAllocation(settlement, now);
        SettlementExpense savedExpense = saveSettlementExpensePort.save(expense);
        List<SettlementItem> savedItems = saveSettlementItemPort.saveAll(items);
        return SettlementReceiptExpenseMutationResult.of(
                savedExpense,
                savedItems,
                totalExpenseAmount
        );
    }

    private ValidatedExtraction validate(ExtractReceiptItemsPort.ReceiptExtraction extraction) {
        if (extraction == null
                || extraction.description() == null
                || extraction.description().strip().isBlank()
                || extraction.description().strip().length() > 500
                || extraction.totalAmount() <= 0
                || extraction.discount() < 0
                || extraction.deliveryFee() < 0
                || extraction.items() == null
                || extraction.items().isEmpty()) {
            throw invalidExtraction();
        }
        long itemTotal = 0L;
        List<ExtractReceiptItemsPort.ReceiptItem> items = new ArrayList<>();
        try {
            for (ExtractReceiptItemsPort.ReceiptItem item : extraction.items()) {
                if (item == null
                        || item.name() == null
                        || item.name().strip().isBlank()
                        || item.name().strip().length() > 500
                        || item.quantity() <= 0
                        || item.amount() < 0
                        || item.discount() < 0
                        || item.discount() > item.amount()
                        || item.options() == null) {
                    throw invalidExtraction();
                }
                List<ExtractReceiptItemsPort.ReceiptOption> options = new ArrayList<>();
                for (ExtractReceiptItemsPort.ReceiptOption option : item.options()) {
                    if (option == null || option.name() == null
                            || option.name().isBlank()
                            || option.name().strip().length() > 500
                            || option.price() < 0) {
                        throw invalidExtraction();
                    }
                    options.add(new ExtractReceiptItemsPort.ReceiptOption(
                            option.name().strip(), option.price()
                    ));
                }
                itemTotal = Math.addExact(itemTotal,
                        Math.subtractExact(item.amount(), item.discount()));
                items.add(new ExtractReceiptItemsPort.ReceiptItem(
                        item.name().strip(),
                        item.quantity(),
                        item.amount(),
                        item.discount(),
                        List.copyOf(options)
                ));
            }
            if (extraction.discount() >= itemTotal
                    || Math.addExact(itemTotal - extraction.discount(), extraction.deliveryFee())
                    != extraction.totalAmount()) {
                throw invalidExtraction();
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_INVALID,
                    exception
            );
        }
        return new ValidatedExtraction(
                extraction.description().strip(),
                extraction.totalAmount(),
                List.copyOf(items),
                extraction.discount(),
                extraction.deliveryFee()
        );
    }

    private List<SettlementItem> toItems(
            UUID settlementId,
            UUID expenseId,
            List<ExtractReceiptItemsPort.ReceiptItem> extractedItems,
            LocalDateTime now
    ) {
        List<SettlementItem> items = new ArrayList<>(extractedItems.size());
        for (int index = 0; index < extractedItems.size(); index++) {
            ExtractReceiptItemsPort.ReceiptItem item = extractedItems.get(index);
            items.add(SettlementItem.fromReceipt(
                    settlementId,
                    expenseId,
                    item.name(),
                    item.quantity(),
                    item.amount(),
                    index,
                    now,
                    item.discount(),
                    item.options().stream()
                            .map(option -> new SettlementItem.OptionInput(
                                    option.name(), option.price()
                            ))
                            .toList()
            ));
        }
        return List.copyOf(items);
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

    private void invalidateAllocation(Settlement settlement, LocalDateTime now) {
        Settlement invalidated = allocationDraftInvalidator.invalidate(settlement, now);
        if (invalidated != settlement) {
            saveSettlementPort.update(invalidated);
        }
    }

    private ApplicationException invalidExtraction() {
        return ApplicationException.of(
                SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_INVALID
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record ValidatedExtraction(
            String description,
            long totalAmount,
            List<ExtractReceiptItemsPort.ReceiptItem> items,
            long discount,
            long deliveryFee
    ) {
    }
}
