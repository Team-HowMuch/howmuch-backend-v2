package com.amateur.howmuchbackend.domain.settlement.netting;

import java.util.UUID;

/** Original obligations retain their actual deposits; only the remainder is netted. */
public record NettingDebt(
        UUID settlementId,
        UUID shareId,
        long payerId,
        long receiverId,
        long amount,
        long confirmedAmount) {
    public NettingDebt {
        if (settlementId == null
                || shareId == null
                || payerId <= 0
                || receiverId <= 0
                || payerId == receiverId
                || amount <= 0
                || confirmedAmount < 0
                || confirmedAmount > amount) {
            throw new IllegalArgumentException("Invalid original debt");
        }
    }

    public long remainingAmount() {
        return Math.subtractExact(amount, confirmedAmount);
    }
}
