package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영수증에서 읽은 상호명과 결제일.
 *
 * <p>정산 상세와 링크 진입이 같은 값을 보여줘야 해서 판정을 한 곳에 둔다.
 *
 * <pre>
 * SettlementReceiptInfo receipt = SettlementReceiptInfo.resolve(expenses);
 * receipt.storeName();  // 영수증이 한 건이 아니면 null
 * </pre>
 */
record SettlementReceiptInfo(
        String storeName,
        LocalDateTime paidAt
) {
    private static final SettlementReceiptInfo EMPTY = new SettlementReceiptInfo(null, null);

    /** 영수증 지출이 정확히 한 건일 때만 어느 가게에서 언제 결제했는지 확정할 수 있다. */
    static SettlementReceiptInfo resolve(List<SettlementExpense> expenses) {
        if (expenses == null) {
            return EMPTY;
        }
        List<SettlementExpense> receiptExpenses = expenses.stream()
                .filter(expense -> expense.source() == SettlementExpenseSource.RECEIPT_OCR)
                .toList();
        if (receiptExpenses.size() != 1) {
            return EMPTY;
        }
        SettlementExpense receiptExpense = receiptExpenses.getFirst();
        return new SettlementReceiptInfo(
                receiptExpense.description(),
                receiptExpense.paidAt()
        );
    }
}
