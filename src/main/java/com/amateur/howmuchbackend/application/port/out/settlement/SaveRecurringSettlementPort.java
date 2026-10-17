package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;

public interface SaveRecurringSettlementPort {
    void save(RecurringSettlement recurring);

    void update(RecurringSettlement recurring);
}
