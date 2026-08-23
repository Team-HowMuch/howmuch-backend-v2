package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.MatchSettlementContactsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementContactCandidateResult;

import java.util.List;
import java.util.UUID;

public interface MatchSettlementContactsUseCase {
    List<SettlementContactCandidateResult> match(
            UUID settlementId,
            MatchSettlementContactsCommand command
    );
}
