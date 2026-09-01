package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemSelectionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemSubmissionJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;

public final class SettlementItemAllocationMapper {
    private SettlementItemAllocationMapper() {
    }

    public static SettlementItemSelectionJpaEntity toEntity(
            SettlementItemSelection selection
    ) {
        return new SettlementItemSelectionJpaEntity(selection);
    }

    public static SettlementItemSelection toDomain(
            SettlementItemSelectionJpaEntity entity
    ) {
        return new SettlementItemSelection(
                entity.getId(),
                entity.getSettlementId(),
                entity.getItemId(),
                entity.getParticipantId(),
                entity.getFraction(),
                entity.getUnits(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public static SettlementItemSubmissionJpaEntity toEntity(
            SettlementItemSubmission submission
    ) {
        return new SettlementItemSubmissionJpaEntity(submission);
    }

    public static SettlementItemSubmission toDomain(
            SettlementItemSubmissionJpaEntity entity
    ) {
        return new SettlementItemSubmission(
                entity.getId(),
                entity.getSettlementId(),
                entity.getParticipantId(),
                entity.getSubmittedAt()
        );
    }
}
