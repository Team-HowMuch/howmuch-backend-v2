package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;

import java.util.List;

public interface GetSettlementsUseCase {
    List<SettlementListItemResult> get(GetSettlementsCommand command);
}
