package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.util.Objects;
import java.util.UUID;

public record SettlementItemOption(
        UUID id,
        UUID settlementId,
        UUID itemId,
        String name,
        long price,
        int displayOrder
) {
    public static final int MAX_NAME_LENGTH = 500;

    public SettlementItemOption {
        Objects.requireNonNull(id);
        Objects.requireNonNull(settlementId);
        Objects.requireNonNull(itemId);
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH
                || price < 0 || displayOrder < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST);
        }
        name = name.strip();
    }

    public static SettlementItemOption create(
            UUID settlementId, UUID itemId, String name, long price, int displayOrder
    ) {
        return new SettlementItemOption(UUID.randomUUID(), settlementId, itemId, name, price, displayOrder);
    }
}
