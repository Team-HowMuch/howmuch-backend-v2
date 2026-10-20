package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.RecordRecurringSettlementFailureCommand;

public interface RecordRecurringSettlementFailureUseCase {
    void record(RecordRecurringSettlementFailureCommand command);
}
