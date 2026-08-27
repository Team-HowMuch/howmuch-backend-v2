package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;

import java.util.UUID;

public interface GetSettlementTransferInstructionUseCase {
    SettlementTransferInstructionResult get(
            String rawLinkToken,
            UUID participantContextToken
    );
}
