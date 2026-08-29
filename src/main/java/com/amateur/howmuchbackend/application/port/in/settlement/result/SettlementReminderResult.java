package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;

public record SettlementReminderResult(
        LocalDateTime remindedAt,
        LocalDateTime nextAvailableAt
) {
}
