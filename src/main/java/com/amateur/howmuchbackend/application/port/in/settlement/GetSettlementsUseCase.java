package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListPageResult;

public interface GetSettlementsUseCase {
    SettlementListPageResult get(GetSettlementsCommand command);
}
