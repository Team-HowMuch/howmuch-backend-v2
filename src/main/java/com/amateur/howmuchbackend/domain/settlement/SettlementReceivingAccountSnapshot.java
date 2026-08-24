package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record SettlementReceivingAccountSnapshot(
        UUID settlementId,
        Long sourceAccountId,
        String organization,
        String accountNumber,
        String accountHolder,
        LocalDateTime createdAt
) {
    public static final int MAX_ACCOUNT_HOLDER_LENGTH = 100;
    private static final Pattern ORGANIZATION_PATTERN = Pattern.compile("^[0-9]{4}$");

    public SettlementReceivingAccountSnapshot {
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(createdAt, "수금 계좌 스냅샷 생성 시각은 필수입니다.");
        if (sourceAccountId == null || sourceAccountId <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_ID
            );
        }
        if (organization == null || !ORGANIZATION_PATTERN.matcher(organization).matches()) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_SNAPSHOT
            );
        }
        accountNumber = normalizeRequired(
                accountNumber,
                Integer.MAX_VALUE,
                SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_SNAPSHOT
        );
        accountHolder = normalizeRequired(
                accountHolder,
                MAX_ACCOUNT_HOLDER_LENGTH,
                SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_SNAPSHOT
        );
    }

    public static SettlementReceivingAccountSnapshot create(
            UUID settlementId,
            Long sourceAccountId,
            String organization,
            String accountNumber,
            String accountHolder,
            LocalDateTime now
    ) {
        return new SettlementReceivingAccountSnapshot(
                settlementId,
                sourceAccountId,
                organization,
                accountNumber,
                accountHolder,
                now
        );
    }

    private static String normalizeRequired(
            String value,
            int maxLength,
            SettlementStatusCode statusCode
    ) {
        if (value == null) {
            throw ApplicationException.of(statusCode);
        }
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw ApplicationException.of(statusCode);
        }
        return normalized;
    }
}
