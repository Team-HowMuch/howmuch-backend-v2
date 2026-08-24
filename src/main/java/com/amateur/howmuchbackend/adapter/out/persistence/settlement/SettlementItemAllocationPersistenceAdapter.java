package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementItemAllocationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemSelectionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemSubmissionJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementItemAllocationPersistenceAdapter
        implements SettlementItemAllocationStorePort {
    private static final String SUBMISSION_UNIQUE_CONSTRAINT =
            "uk_settlement_item_submissions_settlement_participant";

    private final SettlementItemSelectionJpaRepository selectionRepository;
    private final SettlementItemSubmissionJpaRepository submissionRepository;

    @Override
    public List<SettlementItemSelection> findAllSelections(UUID settlementId) {
        return selectionRepository.findAllBySettlementId(settlementId)
                .stream()
                .map(SettlementItemAllocationMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<SettlementItemSelection> findSelection(
            UUID settlementId,
            UUID itemId,
            UUID participantId
    ) {
        return selectionRepository
                .findBySettlementIdAndItemIdAndParticipantId(
                        settlementId,
                        itemId,
                        participantId
                )
                .map(SettlementItemAllocationMapper::toDomain);
    }

    @Override
    public SettlementItemSelection saveSelection(
            SettlementItemSelection selection
    ) {
        var existing = selectionRepository
                .findBySettlementIdAndItemIdAndParticipantId(
                        selection.settlementId(),
                        selection.itemId(),
                        selection.participantId()
                );
        if (existing.isPresent()) {
            existing.get().updateFrom(selection);
            return SettlementItemAllocationMapper.toDomain(
                    selectionRepository.saveAndFlush(existing.get())
            );
        }
        return SettlementItemAllocationMapper.toDomain(
                selectionRepository.saveAndFlush(
                        SettlementItemAllocationMapper.toEntity(selection)
                )
        );
    }

    @Override
    public void deleteSelection(SettlementItemSelection selection) {
        var entity = selectionRepository
                .findBySettlementIdAndItemIdAndParticipantId(
                        selection.settlementId(),
                        selection.itemId(),
                        selection.participantId()
                )
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_FOUND
                ));
        selectionRepository.delete(entity);
        selectionRepository.flush();
    }

    @Override
    public List<SettlementItemSubmission> findAllSubmissions(UUID settlementId) {
        return submissionRepository.findAllBySettlementId(settlementId)
                .stream()
                .map(SettlementItemAllocationMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<SettlementItemSubmission> findSubmission(
            UUID settlementId,
            UUID participantId
    ) {
        return submissionRepository
                .findBySettlementIdAndParticipantId(settlementId, participantId)
                .map(SettlementItemAllocationMapper::toDomain);
    }

    @Override
    public SettlementItemSubmission saveSubmission(
            SettlementItemSubmission submission
    ) {
        try {
            return SettlementItemAllocationMapper.toDomain(
                    submissionRepository.saveAndFlush(
                            SettlementItemAllocationMapper.toEntity(submission)
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, SUBMISSION_UNIQUE_CONSTRAINT)) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS,
                        exception
                );
            }
            throw exception;
        }
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null
                    && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
