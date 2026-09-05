package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영수증 또는 결제내역 지출의 상호명과 결제일.
 *
 * <p>정산 상세와 링크 진입이 같은 값을 보여줘야 해서 판정을 한 곳에 둔다.
 *
 * <pre>
 * SettlementPaymentInfo payment = SettlementPaymentInfo.resolve(expenses);
 * payment.storeName();  // 표시 대상 지출이 한 건이 아니면 null
 * </pre>
 */
record SettlementPaymentInfo(
        String storeName,
        LocalDateTime paidAt
) {
    private static final SettlementPaymentInfo EMPTY = new SettlementPaymentInfo(null, null);

    /** 영수증·결제내역 지출을 합쳐 정확히 한 건일 때만 어느 가게에서 언제 결제했는지 확정할 수 있다. */
    static SettlementPaymentInfo resolve(List<SettlementExpense> expenses) {
        if (expenses == null) {
            return EMPTY;
        }
        List<SettlementExpense> paymentExpenses = expenses.stream()
                .filter(expense -> expense.source() == SettlementExpenseSource.RECEIPT_OCR
                        || expense.source() == SettlementExpenseSource.PAYMENT_HISTORY)
                .toList();
        if (paymentExpenses.size() != 1) {
            return EMPTY;
        }
        SettlementExpense paymentExpense = paymentExpenses.getFirst();
        return new SettlementPaymentInfo(
                paymentExpense.description(),
                paymentExpense.paidAt()
        );
    }
}
