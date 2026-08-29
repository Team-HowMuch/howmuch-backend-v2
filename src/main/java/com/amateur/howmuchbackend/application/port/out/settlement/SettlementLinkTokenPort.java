package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;

public interface SettlementLinkTokenPort {
    IssuedSettlementLinkToken issue(LocalDateTime now);

    String hash(String rawToken);

    String url(String rawToken);

    record IssuedSettlementLinkToken(
            String tokenHash,
            String rawToken,
            String url,
            LocalDateTime expiresAt
    ) {
    }
}
