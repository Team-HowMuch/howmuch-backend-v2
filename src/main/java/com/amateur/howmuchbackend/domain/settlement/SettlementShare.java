package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementShare(
        UUID id,
        UUID settlementId,
        UUID payerParticipantId,
        long amount,
        LocalDateTime createdAt
) {
    public SettlementShare {
        Objects.requireNonNull(id, "정산 Share ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(payerParticipantId, "송금 참여자 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "정산 Share 생성 시각은 필수입니다.");
        if (amount <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_SHARE_AMOUNT);
        }
    }

    public static SettlementShare create(
            UUID settlementId,
            UUID payerParticipantId,
            long amount,
            LocalDateTime now
    ) {
        return new SettlementShare(
                UUID.randomUUID(),
                settlementId,
                payerParticipantId,
                amount,
                now
        );
    }
}
