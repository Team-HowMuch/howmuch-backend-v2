package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;

import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementParticipantContextPort {
    Optional<SettlementParticipantContext> findByToken(UUID contextToken);
}
