package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

public interface SaveSettlementItemSelectionUseCase {
    SettlementItemAllocationResult save(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    );
}
