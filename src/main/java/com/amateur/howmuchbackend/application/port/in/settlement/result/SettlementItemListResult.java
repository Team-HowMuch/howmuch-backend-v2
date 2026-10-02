package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;

public record SettlementItemListResult(
        List<SettlementItemResult> items,
        long totalItemAmount,
        long discount,
        long deliveryFee
) {
    public SettlementItemListResult(List<SettlementItemResult> items, long totalItemAmount, long discount) {
        this(items, totalItemAmount, discount, 0);
    }

    public SettlementItemListResult(List<SettlementItemResult> items, long totalItemAmount) {
        this(items, totalItemAmount, 0);
    }

    public static SettlementItemListResult from(List<SettlementItem> items) {
        return from(items, 0);
    }

    public static SettlementItemListResult from(
            List<SettlementItem> items, long receiptDiscount
    ) {
        return from(items, receiptDiscount, 0);
    }

    public static SettlementItemListResult from(
            List<SettlementItem> items, long receiptDiscount, long deliveryFee
    ) {
        long total = 0L;
        for (SettlementItem item : items) {
            total = Math.addExact(total, item.amount());
        }
        return new SettlementItemListResult(
                items.stream().map(SettlementItemResult::from).toList(),
                total,
                receiptDiscount,
                deliveryFee
        );
    }
}
