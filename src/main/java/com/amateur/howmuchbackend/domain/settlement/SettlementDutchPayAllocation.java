package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementDutchPayAllocation(
        UUID id,
        UUID settlementId,
        UUID participantId,
        long amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public SettlementDutchPayAllocation {
        Objects.requireNonNull(id, "더치페이 배분 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(participantId, "정산 참여자 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "더치페이 배분 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "더치페이 배분 수정 시각은 필수입니다.");
        validateAmount(amount);
    }

    public static SettlementDutchPayAllocation create(
            UUID settlementId,
            UUID participantId,
            long amount,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "더치페이 배분 생성 시각은 필수입니다.");
        return new SettlementDutchPayAllocation(
                UUID.randomUUID(),
                settlementId,
                participantId,
                amount,
                now,
                now
        );
    }

    public SettlementDutchPayAllocation updateAmount(long newAmount, LocalDateTime now) {
        return new SettlementDutchPayAllocation(
                id,
                settlementId,
                participantId,
                newAmount,
                createdAt,
                Objects.requireNonNull(now, "더치페이 배분 수정 시각은 필수입니다.")
        );
    }

    private static void validateAmount(long amount) {
        if (amount < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_DUTCH_PAY_AMOUNT);
        }
    }
}
