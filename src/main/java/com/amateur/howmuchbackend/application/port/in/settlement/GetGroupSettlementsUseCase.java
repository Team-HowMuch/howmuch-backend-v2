package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetGroupSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.GroupSettlementListItemResult;
import java.util.List;

public interface GetGroupSettlementsUseCase {
    List<GroupSettlementListItemResult> get(GetGroupSettlementsCommand command);
}
