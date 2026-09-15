package com.amateur.howmuchbackend.domain.treasurer;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 답변 한 건에 대한 신고.
 *
 * <p>한 답변에는 신고가 하나뿐이고, 신고자는 그 답변을 받은 사용자뿐이다. 두 규칙은 스키마가 강제한다.
 * 이 모델은 상태 전이를 지킨다. {@code OPEN}에서 {@code RESOLVED}로 한 방향으로만 간다.
 */
public record TreasurerAnswerReport(
        UUID id,
        UUID answerId,
        Long reporterUserId,
        TreasurerAnswerReportReason reason,
        String detail,
        TreasurerAnswerReportStatus status,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {
    public static final int MAX_DETAIL_LENGTH = 500;

    public TreasurerAnswerReport {
        Objects.requireNonNull(id, "신고 식별자는 필수입니다.");
        Objects.requireNonNull(answerId, "신고 대상 답변은 필수입니다.");
        Objects.requireNonNull(reporterUserId, "신고자는 필수입니다.");
        Objects.requireNonNull(reason, "신고 사유는 필수입니다.");
        Objects.requireNonNull(status, "처리 상태는 필수입니다.");
        Objects.requireNonNull(createdAt, "신고 시각은 필수입니다.");
        detail = normalizeDetail(detail);
        if ((status == TreasurerAnswerReportStatus.RESOLVED) != (resolvedAt != null)) {
            throw new IllegalArgumentException("처리 시각은 처리 상태와 함께 채워지고 함께 비워집니다.");
        }
    }

    public static TreasurerAnswerReport open(
            UUID answerId,
            Long reporterUserId,
            TreasurerAnswerReportReason reason,
            String detail,
            LocalDateTime now
    ) {
        return new TreasurerAnswerReport(
                UUID.randomUUID(),
                answerId,
                reporterUserId,
                reason,
                detail,
                TreasurerAnswerReportStatus.OPEN,
                now,
                null
        );
    }

    public TreasurerAnswerReport resolve(LocalDateTime now) {
        if (status == TreasurerAnswerReportStatus.RESOLVED) {
            throw new IllegalStateException("이미 처리된 신고입니다.");
        }
        Objects.requireNonNull(now, "처리 시각은 필수입니다.");
        return new TreasurerAnswerReport(
                id, answerId, reporterUserId, reason, detail,
                TreasurerAnswerReportStatus.RESOLVED, createdAt, now);
    }

    /**
     * 공백만 있는 상세는 없는 것으로 본다. 스키마의 not-blank 제약과 같은 규칙이다.
     */
    private static String normalizeDetail(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "신고 상세는 %d자 이하여야 합니다.".formatted(MAX_DETAIL_LENGTH));
        }
        return trimmed;
    }
}
