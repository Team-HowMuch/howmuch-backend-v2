package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record SettlementLink(
        UUID id,
        UUID settlementId,
        String tokenHash,
        SettlementLinkStatus status,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final int TOKEN_HASH_LENGTH = 64;
    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public SettlementLink {
        Objects.requireNonNull(id, "정산 링크 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(status, "정산 링크 상태는 필수입니다.");
        Objects.requireNonNull(expiresAt, "정산 링크 만료 시각은 필수입니다.");
        Objects.requireNonNull(createdAt, "정산 링크 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "정산 링크 수정 시각은 필수입니다.");
        validateTokenHash(tokenHash);
        if (!expiresAt.isAfter(createdAt)) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_LINK_EXPIRATION
            );
        }
    }

    public static SettlementLink active(
            UUID settlementId,
            String tokenHash,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "정산 링크 생성 시각은 필수입니다.");
        return new SettlementLink(
                UUID.randomUUID(),
                settlementId,
                tokenHash,
                SettlementLinkStatus.ACTIVE,
                expiresAt,
                now,
                now
        );
    }

    public boolean isUsableAt(LocalDateTime now) {
        Objects.requireNonNull(now, "정산 링크 확인 시각은 필수입니다.");
        return status == SettlementLinkStatus.ACTIVE && expiresAt.isAfter(now);
    }

    public SettlementLink reissue(
            String newTokenHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now
    ) {
        if (status != SettlementLinkStatus.ACTIVE) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
        }
        return new SettlementLink(
                id,
                settlementId,
                newTokenHash,
                status,
                newExpiresAt,
                createdAt,
                Objects.requireNonNull(now, "정산 링크 재발급 시각은 필수입니다.")
        );
    }

    private static void validateTokenHash(String tokenHash) {
        if (tokenHash == null || !TOKEN_HASH_PATTERN.matcher(tokenHash).matches()) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_LINK_TOKEN_HASH
            );
        }
    }
}
