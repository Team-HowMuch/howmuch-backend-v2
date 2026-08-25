package com.amateur.howmuchbackend.domain.settlement;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementDeposit(
        UUID id,
        UUID settlementId,
        UUID shareId,
        Long sourceAccountId,
        Long accountTransactionId,
        LocalDateTime matchedAt
) {
    public SettlementDeposit {
        Objects.requireNonNull(id, "정산 입금 확인 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(shareId, "정산 Share ID는 필수입니다.");
        Objects.requireNonNull(matchedAt, "입금 확인 시각은 필수입니다.");
        if (sourceAccountId == null || sourceAccountId <= 0) {
            throw new IllegalArgumentException("수금 계좌 ID는 양수여야 합니다.");
        }
        if (accountTransactionId == null || accountTransactionId <= 0) {
            throw new IllegalArgumentException("계좌 거래 ID는 양수여야 합니다.");
        }
    }

    public static SettlementDeposit create(
            UUID settlementId,
            UUID shareId,
            Long sourceAccountId,
            Long accountTransactionId,
            LocalDateTime matchedAt
    ) {
        return new SettlementDeposit(
                UUID.randomUUID(),
                settlementId,
                shareId,
                sourceAccountId,
                accountTransactionId,
                matchedAt
        );
    }
}
