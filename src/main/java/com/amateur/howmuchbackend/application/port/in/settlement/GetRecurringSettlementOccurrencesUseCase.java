package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementPageCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementOccurrenceResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPageResult;
import java.util.UUID;

public interface GetRecurringSettlementOccurrencesUseCase {
    RecurringSettlementPageResult<RecurringSettlementOccurrenceResult> get(Long groupId, UUID recurringSettlementId,
            RecurringSettlementPageCommand query);
}
