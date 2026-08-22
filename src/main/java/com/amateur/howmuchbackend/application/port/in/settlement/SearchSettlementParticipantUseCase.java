package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SearchSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;

import java.util.List;
import java.util.UUID;

public interface SearchSettlementParticipantUseCase {
    List<SettlementParticipantCandidateResult> searchByPublicId(
            UUID settlementId,
            SearchSettlementParticipantCommand command
    );
}
