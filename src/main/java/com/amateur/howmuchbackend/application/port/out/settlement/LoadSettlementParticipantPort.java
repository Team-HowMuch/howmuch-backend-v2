package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementParticipantPort {
    List<SettlementParticipant> findAllBySettlementId(UUID settlementId);

    Optional<SettlementParticipant> findBySettlementIdAndId(UUID settlementId, UUID participantId);

    boolean existsBySettlementIdAndLinkedUserId(UUID settlementId, Long linkedUserId);

    boolean existsBySettlementIdAndPhoneHash(UUID settlementId, String phoneHash);

    int nextDisplayOrder(UUID settlementId);
}
