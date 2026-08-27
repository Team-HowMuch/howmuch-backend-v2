package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;

public record SettlementItemListResult(
        List<SettlementItemResult> items,
        long totalItemAmount
) {
    public static SettlementItemListResult from(List<SettlementItem> items) {
        long total = 0L;
        for (SettlementItem item : items) {
            total = Math.addExact(total, item.amount());
        }
        return new SettlementItemListResult(
                items.stream().map(SettlementItemResult::from).toList(),
                total
        );
    }
}
