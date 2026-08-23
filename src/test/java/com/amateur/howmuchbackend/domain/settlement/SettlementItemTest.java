package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 품목 Domain 규칙")
class SettlementItemTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID EXPENSE_ID = UUID.fromString(
            "798cb149-f804-4427-9194-73ebdd7ff2eb"
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 20, 0);

    @Test
    @DisplayName("OCR 품목 이름을 정규화하고 수량이 반영된 행 금액을 보존한다")
    void createsNormalizedReceiptItem() {
        SettlementItem item = SettlementItem.fromReceipt(
                SETTLEMENT_ID,
                EXPENSE_ID,
                "  아메리카노  ",
                2,
                10_000,
                0,
                NOW
        );

        assertThat(item.name()).isEqualTo("아메리카노");
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(item.amount()).isEqualTo(10_000);
        assertThat(item.displayOrder()).isZero();
    }

    @Test
    @DisplayName("화면에 빈 품목이 생기지 않도록 유니코드 공백 이름을 거부한다")
    void rejectsUnicodeWhitespaceName() {
        assertThatThrownBy(() -> item("\u2003", 1, 1_000, 0))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_NAME
                        ));
    }

    @Test
    @DisplayName("수량과 행 금액이 양수가 아니면 OCR 품목을 거부한다")
    void rejectsNonPositiveQuantityAndAmount() {
        assertThatThrownBy(() -> item("커피", 0, 1_000, 0))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_QUANTITY
                        ));
        assertThatThrownBy(() -> item("커피", 1, 0, 0))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_AMOUNT
                        ));
    }

    @Test
    @DisplayName("품목 표시 순서가 음수이면 거부한다")
    void rejectsNegativeDisplayOrder() {
        assertThatThrownBy(() -> item("커피", 1, 1_000, -1))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_ORDER
                        ));
    }

    private SettlementItem item(String name, int quantity, long amount, int displayOrder) {
        return SettlementItem.fromReceipt(
                SETTLEMENT_ID,
                EXPENSE_ID,
                name,
                quantity,
                amount,
                displayOrder,
                NOW
        );
    }
}
