package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ChangeMySettlementCategoryCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;

import java.util.UUID;

public interface ChangeMySettlementCategoryUseCase {
    SettlementCategoryResult change(UUID settlementId, ChangeMySettlementCategoryCommand command);
}
