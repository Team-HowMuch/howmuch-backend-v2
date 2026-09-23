package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 지출 Domain 규칙")
class SettlementExpenseTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 9, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 22, 19, 30);

    @Test
    @DisplayName("수동 지출 설명의 앞뒤 공백을 제거하고 양수 금액을 보존한다")
    void manualExpense_descriptionTrimmed_positiveAmountPreserved() {
        SettlementExpense expense = SettlementExpense.manual(
                SETTLEMENT_ID,
                "  저녁 식사  ",
                42_000,
                0,
                NOW
        );

        assertThat(expense.source()).isEqualTo(SettlementExpenseSource.MANUAL);
        assertThat(expense.description()).isEqualTo("저녁 식사");
        assertThat(expense.amount()).isEqualTo(42_000);
        assertThat(expense.displayOrder()).isZero();
    }

    @Test
    @DisplayName("화면에 빈 지출이 생기지 않도록 유니코드 공백 설명을 거부한다")
    void rejectsUnicodeWhitespaceDescription() {
        assertThatThrownBy(() -> SettlementExpense.manual(
                SETTLEMENT_ID,
                "\u2003",
                42_000,
                0,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_DESCRIPTION
                ));
    }

    @Test
    @DisplayName("정산 금액 합계가 감소하지 않도록 0원 이하 지출을 거부한다")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> SettlementExpense.manual(
                SETTLEMENT_ID,
                "저녁 식사",
                0,
                0,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_AMOUNT
                ));
    }

    @Test
    @DisplayName("부분 수정에서 전달되지 않은 금액을 보존한다")
    void updatesOnlyRequestedField() {
        SettlementExpense expense = manualExpense();

        SettlementExpense updated = expense.updateManual(
                "저녁 식사와 음료",
                null,
                NOW.plusMinutes(1)
        );

        assertThat(updated.description()).isEqualTo("저녁 식사와 음료");
        assertThat(updated.amount()).isEqualTo(42_000);
        assertThat(updated.updatedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    @DisplayName("외부 원본의 수정 정책이 확정되기 전에는 수동 지출 API 수정을 거부한다")
    void rejectsUpdateForImportedExpense() {
        SettlementExpense imported = SettlementExpense.paymentHistory(
                SETTLEMENT_ID,
                1L,
                "  transaction-key  ",
                "카드 결제",
                42_000,
                PAID_AT,
                0,
                NOW
        );

        assertThat(imported.paidAt()).isEqualTo(PAID_AT);
        assertThat(imported.createdAt()).isEqualTo(NOW);
        assertThat(imported.description()).isEqualTo("카드 결제");
        assertThat(imported.sourceReference()).isEqualTo("transaction-key");
        assertThat(imported.sourceAccountId()).isEqualTo(1L);
        assertThatThrownBy(() -> imported.updateManual("변경", null, NOW.plusMinutes(1)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_EDITABLE
                        ));
    }

    @Test
    @DisplayName("OCR 영수증 지출은 원본 참조와 출처를 보존한다")
    void createsReceiptOcrExpense() {
        SettlementExpense expense = SettlementExpense.receiptOcr(
                SETTLEMENT_ID,
                "  receipt-demo  ",
                "OCR 영수증",
                30_000,
                PAID_AT,
                0,
                NOW
        );

        assertThat(expense.source()).isEqualTo(SettlementExpenseSource.RECEIPT_OCR);
        assertThat(expense.sourceReference()).isEqualTo("receipt-demo");
        assertThat(expense.amount()).isEqualTo(30_000);
        assertThat(expense.paidAt()).isEqualTo(PAID_AT);
    }

    @Test
    @DisplayName("수동 지출에 외부 참조가 있거나 결제내역에 외부 참조가 없으면 거부한다")
    void validatesSourceReferenceByExpenseSource() {
        assertThatThrownBy(() -> new SettlementExpense(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                SettlementExpenseSource.MANUAL,
                "transaction-key",
                null,
                "카드 결제",
                42_000,
                0,
                NOW,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
                ));

        assertThatThrownBy(() -> new SettlementExpense(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                SettlementExpenseSource.PAYMENT_HISTORY,
                null,
                1L,
                "카드 결제",
                42_000,
                0,
                NOW,
                NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
                ));
    }

    @Test
    @DisplayName("결제일을 모르는 결제내역도 생성 시각으로 대체하지 않는다")
    void keepsUnknownPaymentDateNull() {
        SettlementExpense expense = SettlementExpense.paymentHistory(
                SETTLEMENT_ID, 1L, "unknown-date", "카드 결제", 42_000, null, 0, NOW
        );

        assertThat(expense.paidAt()).isNull();
        assertThat(expense.createdAt()).isEqualTo(NOW);
    }

    private SettlementExpense manualExpense() {
        return SettlementExpense.manual(
                SETTLEMENT_ID,
                "저녁 식사",
                42_000,
                0,
                NOW
        );
    }
}
