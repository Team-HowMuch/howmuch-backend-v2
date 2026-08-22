package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementParticipantMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementParticipantPersistenceAdapter implements
        LoadSettlementParticipantPort,
        SaveSettlementParticipantPort,
        DeleteSettlementParticipantPort {
    private final SettlementParticipantJpaRepository settlementParticipantJpaRepository;

    @Override
    public SettlementParticipant save(SettlementParticipant participant) {
        try {
            return SettlementParticipantMapper.toDomain(
                    settlementParticipantJpaRepository.saveAndFlush(
                            SettlementParticipantMapper.toEntity(participant)
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
    }

    @Override
    public Optional<SettlementParticipant> findBySettlementIdAndId(
            UUID settlementId,
            UUID participantId
    ) {
        return settlementParticipantJpaRepository.findBySettlementIdAndId(settlementId, participantId)
                .map(SettlementParticipantMapper::toDomain);
    }

    @Override
    public boolean existsBySettlementIdAndLinkedUserId(UUID settlementId, Long linkedUserId) {
        return settlementParticipantJpaRepository
                .existsBySettlementIdAndLinkedUserId(settlementId, linkedUserId);
    }

    @Override
    public boolean existsBySettlementIdAndPhoneHash(UUID settlementId, String phoneHash) {
        return settlementParticipantJpaRepository
                .existsBySettlementIdAndPhoneHash(settlementId, phoneHash);
    }

    @Override
    public int nextDisplayOrder(UUID settlementId) {
        return settlementParticipantJpaRepository
                .findFirstBySettlementIdOrderByDisplayOrderDesc(settlementId)
                .map(participant -> participant.getDisplayOrder() + 1)
                .orElse(0);
    }

    @Override
    public void delete(SettlementParticipant participant) {
        settlementParticipantJpaRepository.deleteById(participant.id());
        settlementParticipantJpaRepository.flush();
    }

    private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
        if (hasConstraint(exception, "uk_settlement_participants_settlement_linked_user")
                || hasConstraint(exception, "uk_settlement_participants_settlement_phone_hash")) {
            return ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_PARTICIPANT_ALREADY_EXISTS,
                    exception
            );
        }
        if (hasConstraint(exception, "uk_settlement_participants_settlement_display_order")) {
            return ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE, exception);
        }
        return exception;
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
