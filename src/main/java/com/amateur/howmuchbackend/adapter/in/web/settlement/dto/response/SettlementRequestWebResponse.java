package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementRequestWebResponse(
        UUID settlementId,
        String url,
        LocalDateTime expiresAt,
        SettlementRequestPhase allocationPhase,
        SettlementStatus status
) {
    public static SettlementRequestWebResponse from(SettlementRequestResult result) {
        return new SettlementRequestWebResponse(
                result.settlementId(),
                result.url(),
                result.expiresAt(),
                result.allocationPhase(),
                result.status()
        );
    }
}
