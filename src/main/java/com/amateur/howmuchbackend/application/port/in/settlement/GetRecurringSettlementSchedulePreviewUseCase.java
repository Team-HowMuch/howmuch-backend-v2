package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementSchedulePreviewCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSchedulePreviewResult;

public interface GetRecurringSettlementSchedulePreviewUseCase {
    RecurringSettlementSchedulePreviewResult preview(Long groupId, RecurringSettlementSchedulePreviewCommand command);
}
