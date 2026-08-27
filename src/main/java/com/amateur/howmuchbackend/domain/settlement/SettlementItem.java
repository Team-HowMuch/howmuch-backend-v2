package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementItem(
        UUID id,
        UUID settlementId,
        UUID expenseId,
        String name,
        int quantity,
        long amount,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final int MAX_NAME_LENGTH = 500;

    public SettlementItem {
        Objects.requireNonNull(id, "정산 품목 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(expenseId, "정산 지출 ID는 필수입니다.");
        name = normalizeName(name);
        validateQuantity(quantity);
        validateAmount(amount);
        validateDisplayOrder(displayOrder);
        Objects.requireNonNull(createdAt, "정산 품목 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "정산 품목 수정 시각은 필수입니다.");
    }

    public static SettlementItem fromReceipt(
            UUID settlementId,
            UUID expenseId,
            String name,
            int quantity,
            long amount,
            int displayOrder,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "정산 품목 생성 시각은 필수입니다.");
        return new SettlementItem(
                UUID.randomUUID(),
                settlementId,
                expenseId,
                name,
                quantity,
                amount,
                displayOrder,
                now,
                now
        );
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_NAME);
        }
        String normalized = name.strip();
        if (normalized.isBlank() || normalized.length() > MAX_NAME_LENGTH) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_NAME);
        }
        return normalized;
    }

    private static void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_QUANTITY);
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_AMOUNT);
        }
    }

    private static void validateDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_ORDER);
        }
    }
}
