package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;

public record SettlementListPageResult(
        List<SettlementListItemResult> items,
        String nextCursor,
        boolean hasNext
) {
}
