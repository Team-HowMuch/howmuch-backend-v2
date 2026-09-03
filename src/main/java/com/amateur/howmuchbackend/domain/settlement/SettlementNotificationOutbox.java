package com.amateur.howmuchbackend.domain.settlement;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementNotificationOutbox(
        UUID id,
        UUID settlementId,
        Long recipientUserId,
        SettlementNotificationOutboxStatus status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        LocalDateTime processingStartedAt,
        LocalDateTime completedAt,
        SettlementNotificationFailureCode lastFailureCode
) {
    public SettlementNotificationOutbox {
        Objects.requireNonNull(id, "알림 Outbox ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(recipientUserId, "알림 수신자 ID는 필수입니다.");
        Objects.requireNonNull(status, "알림 Outbox 상태는 필수입니다.");
        validateState(
                status,
                attemptCount,
                nextAttemptAt,
                processingStartedAt,
                completedAt,
                lastFailureCode
        );
    }

    public static SettlementNotificationOutbox pending(
            UUID settlementId,
            Long recipientUserId,
            LocalDateTime now
    ) {
        return new SettlementNotificationOutbox(
                UUID.randomUUID(),
                settlementId,
                recipientUserId,
                SettlementNotificationOutboxStatus.PENDING,
                0,
                Objects.requireNonNull(now, "알림 처리 가능 시각은 필수입니다."),
                null,
                null,
                null
        );
    }

    public boolean isReadyAt(LocalDateTime now) {
        Objects.requireNonNull(now, "알림 선점 시각은 필수입니다.");
        return (status == SettlementNotificationOutboxStatus.PENDING
                || status == SettlementNotificationOutboxStatus.RETRY)
                && !nextAttemptAt.isAfter(now);
    }

    public SettlementNotificationOutbox claim(LocalDateTime now) {
        if (!isReadyAt(now)) {
            throw new IllegalStateException("처리 가능한 알림 Outbox만 선점할 수 있습니다.");
        }
        return new SettlementNotificationOutbox(
                id,
                settlementId,
                recipientUserId,
                SettlementNotificationOutboxStatus.PROCESSING,
                attemptCount + 1,
                null,
                now,
                null,
                lastFailureCode
        );
    }

    public SettlementNotificationOutbox succeed(LocalDateTime now) {
        requireProcessing();
        return new SettlementNotificationOutbox(
                id,
                settlementId,
                recipientUserId,
                SettlementNotificationOutboxStatus.SUCCEEDED,
                attemptCount,
                null,
                null,
                Objects.requireNonNull(now, "알림 완료 시각은 필수입니다."),
                null
        );
    }

    public SettlementNotificationOutbox retry(
            SettlementNotificationFailureCode failureCode,
            LocalDateTime nextAttemptAt,
            LocalDateTime now
    ) {
        requireProcessing();
        Objects.requireNonNull(now, "알림 재시도 결정 시각은 필수입니다.");
        if (Objects.requireNonNull(nextAttemptAt, "다음 재시도 시각은 필수입니다.")
                .isBefore(now)) {
            throw new IllegalArgumentException("다음 재시도 시각은 결정 시각보다 이를 수 없습니다.");
        }
        return new SettlementNotificationOutbox(
                id,
                settlementId,
                recipientUserId,
                SettlementNotificationOutboxStatus.RETRY,
                attemptCount,
                nextAttemptAt,
                null,
                null,
                Objects.requireNonNull(failureCode, "알림 실패 코드는 필수입니다.")
        );
    }

    public SettlementNotificationOutbox fail(
            SettlementNotificationFailureCode failureCode,
            LocalDateTime now
    ) {
        requireProcessing();
        return new SettlementNotificationOutbox(
                id,
                settlementId,
                recipientUserId,
                SettlementNotificationOutboxStatus.FAILED,
                attemptCount,
                null,
                null,
                Objects.requireNonNull(now, "알림 실패 완료 시각은 필수입니다."),
                Objects.requireNonNull(failureCode, "알림 실패 코드는 필수입니다.")
        );
    }

    public SettlementNotificationOutbox recover(int maxAttempts, LocalDateTime now) {
        requireProcessing();
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("최대 알림 시도 횟수는 1 이상이어야 합니다.");
        }
        return attemptCount >= maxAttempts
                ? fail(SettlementNotificationFailureCode.PROCESSING_TIMEOUT, now)
                : retry(SettlementNotificationFailureCode.PROCESSING_TIMEOUT, now, now);
    }

    private void requireProcessing() {
        if (status != SettlementNotificationOutboxStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 알림 Outbox만 처리 결과를 기록할 수 있습니다.");
        }
    }

    private static void validateState(
            SettlementNotificationOutboxStatus status,
            int attemptCount,
            LocalDateTime nextAttemptAt,
            LocalDateTime processingStartedAt,
            LocalDateTime completedAt,
            SettlementNotificationFailureCode failureCode
    ) {
        if (attemptCount < 0) {
            throw new IllegalArgumentException("알림 시도 횟수는 음수일 수 없습니다.");
        }
        switch (status) {
            case PENDING -> {
                require(attemptCount == 0, "PENDING 알림은 아직 시도되지 않아야 합니다.");
                require(nextAttemptAt != null, "PENDING 알림에는 처리 가능 시각이 필요합니다.");
                require(processingStartedAt == null && completedAt == null && failureCode == null,
                        "PENDING 알림에는 처리·완료·실패 정보가 없어야 합니다.");
            }
            case PROCESSING -> {
                require(attemptCount > 0, "PROCESSING 알림에는 시도 횟수가 필요합니다.");
                require(nextAttemptAt == null && processingStartedAt != null && completedAt == null,
                        "PROCESSING 알림 상태가 올바르지 않습니다.");
            }
            case RETRY -> {
                require(attemptCount > 0, "RETRY 알림에는 시도 횟수가 필요합니다.");
                require(nextAttemptAt != null && processingStartedAt == null && completedAt == null,
                        "RETRY 알림 상태가 올바르지 않습니다.");
                require(failureCode != null, "RETRY 알림에는 실패 코드가 필요합니다.");
            }
            case SUCCEEDED -> {
                require(attemptCount > 0, "SUCCEEDED 알림에는 시도 횟수가 필요합니다.");
                require(nextAttemptAt == null && processingStartedAt == null && completedAt != null,
                        "SUCCEEDED 알림 상태가 올바르지 않습니다.");
                require(failureCode == null, "SUCCEEDED 알림에는 실패 코드가 없어야 합니다.");
            }
            case FAILED -> {
                require(attemptCount > 0, "FAILED 알림에는 시도 횟수가 필요합니다.");
                require(nextAttemptAt == null && processingStartedAt == null && completedAt != null,
                        "FAILED 알림 상태가 올바르지 않습니다.");
                require(failureCode != null, "FAILED 알림에는 실패 코드가 필요합니다.");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
