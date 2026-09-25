package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.List;
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
        LocalDateTime updatedAt,
        long discount,
        List<SettlementItemOption> options
) {
    public static final int MAX_NAME_LENGTH = 500;

    public SettlementItem(
            UUID id, UUID settlementId, UUID expenseId, String name, int quantity,
            long amount, int displayOrder, LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(id, settlementId, expenseId, name, quantity, amount, displayOrder,
                createdAt, updatedAt, 0, List.of());
    }

    public SettlementItem {
        Objects.requireNonNull(id, "정산 품목 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(expenseId, "정산 지출 ID는 필수입니다.");
        name = normalizeName(name);
        validateQuantity(quantity);
        validateAmount(amount);
        validateDisplayOrder(displayOrder);
        if (discount < 0 || discount > amount) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_AMOUNT);
        }
        options = List.copyOf(Objects.requireNonNull(options));
        if (options.stream().anyMatch(option -> !option.settlementId().equals(settlementId)
                || !option.itemId().equals(id))) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST);
        }
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
        return fromReceipt(settlementId, expenseId, name, quantity, amount,
                displayOrder, now, 0, List.of());
    }

    public static SettlementItem fromReceipt(
            UUID settlementId, UUID expenseId, String name, int quantity,
            long amount, int displayOrder, LocalDateTime now,
            long discount, List<OptionInput> optionInputs
    ) {
        Objects.requireNonNull(now, "정산 품목 생성 시각은 필수입니다.");
        UUID id = UUID.randomUUID();
        List<SettlementItemOption> options = java.util.stream.IntStream.range(0, optionInputs.size())
                .mapToObj(index -> SettlementItemOption.create(
                        settlementId, id, optionInputs.get(index).name(),
                        optionInputs.get(index).price(), index
                ))
                .toList();
        return new SettlementItem(
                id,
                settlementId,
                expenseId,
                name,
                quantity,
                amount,
                displayOrder,
                now,
                now,
                discount,
                options
        );
    }

    public long netAmount() {
        return amount - discount;
    }

    public record OptionInput(String name, long price) {
    }

    /** 품목 전체를 채우는 데 필요한 몫 단위. 한 개를 WHOLE_UNITS로 보고 수량만큼 곱한다. */
    public int totalAllocationUnits() {
        return Math.multiplyExact(
                quantity, SettlementItemAllocationFraction.WHOLE_UNITS
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
        if (amount < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_AMOUNT);
        }
    }

    private static void validateDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_ITEM_ORDER);
        }
    }
}
