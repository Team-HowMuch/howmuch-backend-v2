package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.time.LocalDateTime;
import java.util.List;

public record ReplaceSettlementReceiptExpenseCommand(
        String storeName,
        LocalDateTime paidAt,
        List<CreateSettlementReceiptExpenseCommand.Item> items,
        long discount,
        long deliveryFee
) {
}
