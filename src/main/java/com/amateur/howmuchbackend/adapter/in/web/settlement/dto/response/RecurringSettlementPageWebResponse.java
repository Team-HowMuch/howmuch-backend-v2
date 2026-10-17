package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPageResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record RecurringSettlementPageWebResponse<T>(List<T> items, UUID nextCursor) {
    public static <R, W> RecurringSettlementPageWebResponse<W> from(RecurringSettlementPageResult<R> value,
            Function<R, W> mapper) {
        return new RecurringSettlementPageWebResponse<>(value.items().stream().map(mapper).toList(),
                value.nextCursor());
    }
}
