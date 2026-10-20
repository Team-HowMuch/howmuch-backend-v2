package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettedTransferInstructionResult;

import java.util.UUID;

public interface GetNettedTransferInstructionUseCase {
    NettedTransferInstructionResult getInstruction(long groupId, UUID nettingId, UUID transferId);
}
