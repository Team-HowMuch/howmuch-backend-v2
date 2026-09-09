package com.amateur.howmuchbackend.domain.account;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record AccountTransactionSyncJob(
        UUID id,
        Long userId,
        Long accountId,
        UUID idempotencyKey,
        String requestHash,
        LocalDate fromDate,
        LocalDate toDate,
        AccountTransactionSyncStatus status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime lastSyncedAt,
        String failureCode,
        String safeMessage
) {
    public AccountTransactionSyncJob {
        Objects.requireNonNull(id, "동기화 작업 ID는 필수입니다.");
        Objects.requireNonNull(userId, "동기화 요청 사용자 ID는 필수입니다.");
        Objects.requireNonNull(accountId, "동기화 계좌 ID는 필수입니다.");
        Objects.requireNonNull(idempotencyKey, "멱등성 Key는 필수입니다.");
        Objects.requireNonNull(requestHash, "동기화 요청 hash는 필수입니다.");
        Objects.requireNonNull(fromDate, "동기화 시작일은 필수입니다.");
        Objects.requireNonNull(toDate, "동기화 종료일은 필수입니다.");
        Objects.requireNonNull(status, "동기화 작업 상태는 필수입니다.");
        Objects.requireNonNull(requestedAt, "동기화 요청 시각은 필수입니다.");
    }
}
