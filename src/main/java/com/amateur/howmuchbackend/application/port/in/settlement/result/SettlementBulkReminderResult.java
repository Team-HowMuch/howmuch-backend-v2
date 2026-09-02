package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;

public record SettlementBulkReminderResult(
        int sentCount,
        LocalDateTime nextAvailableAt
) {
}
