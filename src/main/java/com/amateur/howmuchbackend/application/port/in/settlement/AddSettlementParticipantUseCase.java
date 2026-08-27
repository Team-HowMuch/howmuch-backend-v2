package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;

import java.util.UUID;

public interface AddSettlementParticipantUseCase {
    SettlementParticipantResult add(UUID settlementId, AddSettlementParticipantCommand command);
}
