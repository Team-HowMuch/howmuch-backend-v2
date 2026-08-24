package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementRequestResult(
        UUID settlementId,
        String url,
        LocalDateTime expiresAt,
        SettlementRequestPhase allocationPhase,
        SettlementStatus status
) {
}
