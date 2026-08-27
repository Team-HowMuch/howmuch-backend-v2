package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

public interface RemoveSettlementItemSelectionUseCase {
    SettlementItemAllocationResult remove(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId
    );
}
