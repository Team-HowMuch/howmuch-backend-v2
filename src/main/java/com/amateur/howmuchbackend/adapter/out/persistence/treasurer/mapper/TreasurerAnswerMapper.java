package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity.TreasurerAnswerJpaEntity;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;

public final class TreasurerAnswerMapper {
    private TreasurerAnswerMapper() {
    }

    public static TreasurerAnswerJpaEntity toEntity(SavedTreasurerAnswer answer) {
        return new TreasurerAnswerJpaEntity(
                answer.id(),
                answer.userId(),
                answer.question(),
                answer.answer(),
                answer.intent(),
                answer.generatedBy(),
                answer.createdAt()
        );
    }

    public static SavedTreasurerAnswer toDomain(TreasurerAnswerJpaEntity entity) {
        return new SavedTreasurerAnswer(
                entity.getId(),
                entity.getUserId(),
                entity.getQuestion(),
                entity.getAnswer(),
                entity.getIntent(),
                entity.getGeneratedBy(),
                entity.getCreatedAt()
        );
    }
}
