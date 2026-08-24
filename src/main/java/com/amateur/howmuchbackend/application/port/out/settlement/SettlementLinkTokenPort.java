package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;

public interface SettlementLinkTokenPort {
    IssuedSettlementLinkToken issue(LocalDateTime now);

    record IssuedSettlementLinkToken(
            String tokenHash,
            String url,
            LocalDateTime expiresAt
    ) {
    }
}
