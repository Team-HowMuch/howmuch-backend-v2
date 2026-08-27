package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantListItemResult;

import java.util.List;
import java.util.UUID;

public interface GetSettlementParticipantsUseCase {
    List<SettlementParticipantListItemResult> get(UUID settlementId);
}
