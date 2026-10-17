package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;
import java.util.UUID;

public record RecurringSettlementPageResult<T>(List<T> items, UUID nextCursor) {
    public RecurringSettlementPageResult {
        items = List.copyOf(items);
    }
}
