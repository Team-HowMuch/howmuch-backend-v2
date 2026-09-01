package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

public interface SaveMySettlementItemSelectionUseCase {
    SettlementItemAllocationResult saveMyItem(
            UUID settlementId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    );
}
