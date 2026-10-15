package com.amateur.howmuchbackend.domain.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

/** 선점한 분류 작업. 결과를 저장할 때 선점 토큰으로 자기 작업인지 확인한다. */
public record SettlementCategoryJob(
        UUID settlementId,
        int attemptCount,
        int maxAttempts,
        UUID leaseToken,
        LocalDateTime leaseUntil
) {
    public boolean lastAttempt() {
        return attemptCount >= maxAttempts;
    }
}
