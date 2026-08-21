package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record Settlement(
        UUID id,
        Long creatorId,
        String title,
        long totalAmount,
        SettlementStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final int MAX_TITLE_LENGTH = 100;

    public Settlement {
        Objects.requireNonNull(id, "정산 ID는 필수입니다.");
        Objects.requireNonNull(creatorId, "정산 생성자 ID는 필수입니다.");
        Objects.requireNonNull(status, "정산 상태는 필수입니다.");
        title = normalizeTitle(title);
        validateAmount(totalAmount);
    }

    public static Settlement draft(Long creatorId, String title, long totalAmount, LocalDateTime now) {
        Objects.requireNonNull(now, "정산 생성 시각은 필수입니다.");
        return new Settlement(
                UUID.randomUUID(),
                creatorId,
                title,
                totalAmount,
                SettlementStatus.DRAFT,
                now,
                now
        );
    }

    public boolean isCreatedBy(Long userId) {
        return creatorId.equals(userId);
    }

    public Settlement updateDraft(String newTitle, Long newTotalAmount, LocalDateTime now) {
        requireDraft();
        if (newTitle == null && newTotalAmount == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_UPDATE);
        }
        return new Settlement(
                id,
                creatorId,
                newTitle == null ? title : newTitle,
                newTotalAmount == null ? totalAmount : newTotalAmount,
                status,
                createdAt,
                Objects.requireNonNull(now, "정산 수정 시각은 필수입니다.")
        );
    }

    public Settlement request(LocalDateTime now) {
        return transition(SettlementStatus.DRAFT, SettlementStatus.REQUESTED, now);
    }

    public Settlement awaitDeposit(LocalDateTime now) {
        return transition(SettlementStatus.REQUESTED, SettlementStatus.AWAITING_DEPOSIT, now);
    }

    public Settlement suspend(LocalDateTime now) {
        return transition(SettlementStatus.REQUESTED, SettlementStatus.SUSPENDED, now);
    }

    public Settlement complete(LocalDateTime now) {
        return transition(SettlementStatus.AWAITING_DEPOSIT, SettlementStatus.COMPLETED, now);
    }

    private Settlement transition(SettlementStatus expected, SettlementStatus next, LocalDateTime now) {
        if (status != expected) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION);
        }
        return new Settlement(
                id,
                creatorId,
                title,
                totalAmount,
                next,
                createdAt,
                Objects.requireNonNull(now, "정산 상태 변경 시각은 필수입니다.")
        );
    }

    private void requireDraft() {
        if (status != SettlementStatus.DRAFT) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_EDITABLE);
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_TITLE);
        }
        String normalized = title.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TITLE_LENGTH) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_TITLE);
        }
        return normalized;
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_AMOUNT);
        }
    }
}
