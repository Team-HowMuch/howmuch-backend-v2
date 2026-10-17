package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import java.util.List;

public interface GetRecurringSettlementHomeUseCase {
    List<RecurringSettlementSummaryResult> getHome(int limit);
}
