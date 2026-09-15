package com.amateur.howmuchbackend.domain.treasurer;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 사용자에게 나간 답변 한 건. 신고 대상을 가리키기 위해 남긴다.
 *
 * <p>보관 기간이 지나면 지운다. 질문 원문에 개인적인 내용이 들어갈 수 있기 때문이다.
 */
public record SavedTreasurerAnswer(
        UUID id,
        Long userId,
        String question,
        String answer,
        InquiryIntent intent,
        TreasurerAnswerGeneratedBy generatedBy,
        LocalDateTime createdAt
) {
    public SavedTreasurerAnswer {
        Objects.requireNonNull(id, "답변 식별자는 필수입니다.");
        Objects.requireNonNull(userId, "답변을 받은 사용자는 필수입니다.");
        Objects.requireNonNull(intent, "질문 의도는 필수입니다.");
        Objects.requireNonNull(generatedBy, "생성 주체는 필수입니다.");
        Objects.requireNonNull(createdAt, "답변 시각은 필수입니다.");
        question = requireText(question, "질문");
        answer = requireText(answer, "답변");
    }

    public static SavedTreasurerAnswer of(
            Long userId,
            String question,
            String answer,
            InquiryIntent intent,
            TreasurerAnswerGeneratedBy generatedBy,
            LocalDateTime createdAt
    ) {
        return new SavedTreasurerAnswer(
                UUID.randomUUID(), userId, question, answer, intent, generatedBy, createdAt);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s은(는) 비어 있을 수 없습니다.".formatted(label));
        }
        return value;
    }
}
