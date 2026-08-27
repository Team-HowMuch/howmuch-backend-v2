package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record SettlementParticipantContext(
        UUID id,
        String linkTokenHash,
        UUID settlementId,
        UUID participantId,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public SettlementParticipantContext {
        Objects.requireNonNull(id, "정산 링크 참여 문맥 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(participantId, "정산 참여자 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "정산 링크 참여 문맥 생성 시각은 필수입니다.");
        Objects.requireNonNull(expiresAt, "정산 링크 참여 문맥 만료 시각은 필수입니다.");
        if (linkTokenHash == null
                || !TOKEN_HASH_PATTERN.matcher(linkTokenHash).matches()
                || !expiresAt.isAfter(createdAt)) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_CONTEXT
            );
        }
    }

    public static SettlementParticipantContext issue(
            String linkTokenHash,
            UUID settlementId,
            UUID participantId,
            LocalDateTime now,
            LocalDateTime expiresAt
    ) {
        return new SettlementParticipantContext(
                UUID.randomUUID(),
                linkTokenHash,
                settlementId,
                participantId,
                now,
                expiresAt
        );
    }

    public String token() {
        return id.toString();
    }

    public boolean isUsableAt(LocalDateTime now) {
        Objects.requireNonNull(now, "참여 문맥 확인 시각은 필수입니다.");
        return now.isBefore(expiresAt);
    }
}
