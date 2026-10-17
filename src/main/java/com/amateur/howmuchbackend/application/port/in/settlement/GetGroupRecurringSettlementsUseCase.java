package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementPageCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPageResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;

public interface GetGroupRecurringSettlementsUseCase {
    RecurringSettlementPageResult<RecurringSettlementSummaryResult> get(Long groupId, RecurringSettlementPageCommand query);
}
