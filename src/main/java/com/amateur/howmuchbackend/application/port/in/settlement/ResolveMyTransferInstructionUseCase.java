package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;

import java.util.UUID;

public interface ResolveMyTransferInstructionUseCase {
    SettlementTransferInstructionResult getMyTransferInstruction(UUID settlementId);
}
