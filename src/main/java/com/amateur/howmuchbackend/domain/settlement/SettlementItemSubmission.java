package com.amateur.howmuchbackend.domain.settlement;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementItemSubmission(
        UUID id,
        UUID settlementId,
        UUID participantId,
        LocalDateTime submittedAt
) {
    public SettlementItemSubmission {
        Objects.requireNonNull(id, "품목 선택 제출 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(participantId, "정산 참여자 ID는 필수입니다.");
        Objects.requireNonNull(submittedAt, "품목 선택 제출 시각은 필수입니다.");
    }

    public static SettlementItemSubmission submit(
            UUID settlementId,
            UUID participantId,
            LocalDateTime now
    ) {
        return new SettlementItemSubmission(
                UUID.randomUUID(),
                settlementId,
                participantId,
                Objects.requireNonNull(now, "품목 선택 제출 시각은 필수입니다.")
        );
    }
}
