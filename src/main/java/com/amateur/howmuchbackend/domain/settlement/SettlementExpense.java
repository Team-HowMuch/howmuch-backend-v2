package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementExpense(
        UUID id,
        UUID settlementId,
        SettlementExpenseSource source,
        String sourceReference,
        Long sourceAccountId,
        String description,
        long amount,
        LocalDateTime paidAt,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long discount,
        long deliveryFee
) {
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_SOURCE_REFERENCE_LENGTH = 255;

    public SettlementExpense(
            UUID id, UUID settlementId, SettlementExpenseSource source,
            String sourceReference, Long sourceAccountId, String description,
            long amount, LocalDateTime paidAt, int displayOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt, long discount
    ) {
        this(id, settlementId, source, sourceReference, sourceAccountId, description,
                amount, paidAt, displayOrder, createdAt, updatedAt, discount, 0);
    }

    public SettlementExpense(
            UUID id, UUID settlementId, SettlementExpenseSource source,
            String sourceReference, Long sourceAccountId, String description,
            long amount, LocalDateTime paidAt, int displayOrder,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(id, settlementId, source, sourceReference, sourceAccountId, description,
                amount, paidAt, displayOrder, createdAt, updatedAt, 0);
    }

    public SettlementExpense(
            UUID id,
            UUID settlementId,
            SettlementExpenseSource source,
            String sourceReference,
            Long sourceAccountId,
            String description,
            long amount,
            int displayOrder,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(
                id,
                settlementId,
                source,
                sourceReference,
                sourceAccountId,
                description,
                amount,
                null,
                displayOrder,
                createdAt,
                updatedAt
        );
    }

    public SettlementExpense(
            UUID id,
            UUID settlementId,
            SettlementExpenseSource source,
            String description,
            long amount,
            int displayOrder,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(
                id,
                settlementId,
                source,
                null,
                null,
                description,
                amount,
                null,
                displayOrder,
                createdAt,
                updatedAt
        );
    }

    public SettlementExpense {
        Objects.requireNonNull(id, "정산 지출 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(source, "정산 지출 원본은 필수입니다.");
        sourceReference = normalizeSourceReference(source, sourceReference);
        sourceAccountId = normalizeSourceAccountId(source, sourceAccountId);
        description = normalizeDescription(description);
        validateAmount(amount);
        if (discount < 0 || deliveryFee < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_AMOUNT);
        }
        if (source != SettlementExpenseSource.RECEIPT_OCR && (discount != 0 || deliveryFee != 0)) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_AMOUNT);
        }
        validateDisplayOrder(displayOrder);
    }

    public static SettlementExpense manual(
            UUID settlementId,
            String description,
            long amount,
            int displayOrder,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "정산 지출 생성 시각은 필수입니다.");
        return new SettlementExpense(
                UUID.randomUUID(),
                settlementId,
                SettlementExpenseSource.MANUAL,
                null,
                null,
                description,
                amount,
                null,
                displayOrder,
                now,
                now
        );
    }

    public static SettlementExpense paymentHistory(
            UUID settlementId,
            Long sourceAccountId,
            String sourceReference,
            String description,
            long amount,
            LocalDateTime paidAt,
            int displayOrder,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "결제내역 지출 생성 시각은 필수입니다.");
        validatePaymentSourceAccountId(sourceAccountId);
        return new SettlementExpense(
                UUID.randomUUID(),
                settlementId,
                SettlementExpenseSource.PAYMENT_HISTORY,
                sourceReference,
                sourceAccountId,
                description,
                amount,
                paidAt,
                displayOrder,
                now,
                now
        );
    }

    public static SettlementExpense receiptOcr(
            UUID settlementId,
            String sourceReference,
            String description,
            long amount,
            int displayOrder,
            LocalDateTime now
    ) {
        return receiptOcr(
                settlementId,
                sourceReference,
                description,
                amount,
                null,
                displayOrder,
                now
        );
    }

    public static SettlementExpense receiptOcr(
            UUID settlementId,
            String sourceReference,
            String description,
            long amount,
            LocalDateTime paidAt,
            int displayOrder,
            LocalDateTime now
    ) {
        return receiptOcr(settlementId, sourceReference, description, amount,
                paidAt, displayOrder, now, 0);
    }

    public static SettlementExpense receiptOcr(
            UUID settlementId, String sourceReference, String description,
            long amount, LocalDateTime paidAt, int displayOrder,
            LocalDateTime now, long discount
    ) {
        return receiptOcr(settlementId, sourceReference, description, amount,
                paidAt, displayOrder, now, discount, 0);
    }

    public static SettlementExpense receiptOcr(
            UUID settlementId, String sourceReference, String description,
            long amount, LocalDateTime paidAt, int displayOrder,
            LocalDateTime now, long discount, long deliveryFee
    ) {
        Objects.requireNonNull(now, "OCR 영수증 지출 생성 시각은 필수입니다.");
        return new SettlementExpense(
                UUID.randomUUID(),
                settlementId,
                SettlementExpenseSource.RECEIPT_OCR,
                sourceReference,
                null,
                description,
                amount,
                paidAt,
                displayOrder,
                now,
                now,
                discount,
                deliveryFee
        );
    }

    public SettlementExpense updateManual(
            String newDescription,
            Long newAmount,
            LocalDateTime now
    ) {
        if (source != SettlementExpenseSource.MANUAL) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_EDITABLE);
        }
        if (newDescription == null && newAmount == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_UPDATE);
        }
        return new SettlementExpense(
                id,
                settlementId,
                source,
                sourceReference,
                sourceAccountId,
                newDescription == null ? description : newDescription,
                newAmount == null ? amount : newAmount,
                paidAt,
                displayOrder,
                createdAt,
                Objects.requireNonNull(now, "정산 지출 수정 시각은 필수입니다."),
                discount,
                deliveryFee
        );
    }

    public SettlementExpense replaceReceipt(
            String storeName, long newAmount, LocalDateTime newPaidAt,
            long newDiscount, long newDeliveryFee, LocalDateTime now
    ) {
        if (source != SettlementExpenseSource.RECEIPT_OCR) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_RECEIPT_REQUIRED);
        }
        return new SettlementExpense(id, settlementId, source, sourceReference, sourceAccountId,
                storeName, newAmount, newPaidAt, displayOrder, createdAt,
                Objects.requireNonNull(now, "영수증 수정 시각은 필수입니다."),
                newDiscount, newDeliveryFee);
    }

    private static String normalizeSourceReference(
            SettlementExpenseSource source,
            String sourceReference
    ) {
        if (source == SettlementExpenseSource.MANUAL) {
            if (sourceReference != null) {
                throw ApplicationException.of(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
                );
            }
            return null;
        }
        if (sourceReference == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
            );
        }
        String normalized = sourceReference.strip();
        if (normalized.isBlank() || normalized.length() > MAX_SOURCE_REFERENCE_LENGTH) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
            );
        }
        return normalized;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_DESCRIPTION);
        }
        String normalized = description.strip();
        if (normalized.isBlank() || normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_DESCRIPTION);
        }
        return normalized;
    }

    private static Long normalizeSourceAccountId(
            SettlementExpenseSource source,
            Long sourceAccountId
    ) {
        if (source != SettlementExpenseSource.PAYMENT_HISTORY) {
            if (sourceAccountId != null) {
                throw ApplicationException.of(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
                );
            }
            return null;
        }
        if (sourceAccountId != null && sourceAccountId <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
            );
        }
        return sourceAccountId;
    }

    private static void validatePaymentSourceAccountId(Long sourceAccountId) {
        if (sourceAccountId == null || sourceAccountId <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_SOURCE_REFERENCE
            );
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_AMOUNT);
        }
    }

    private static void validateDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_ORDER);
        }
    }
}
