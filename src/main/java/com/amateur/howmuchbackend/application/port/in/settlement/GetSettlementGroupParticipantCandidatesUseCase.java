package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;

import java.util.List;
import java.util.UUID;

public interface GetSettlementGroupParticipantCandidatesUseCase {
    List<SettlementParticipantCandidateResult> get(UUID settlementId, Long groupId);
}
