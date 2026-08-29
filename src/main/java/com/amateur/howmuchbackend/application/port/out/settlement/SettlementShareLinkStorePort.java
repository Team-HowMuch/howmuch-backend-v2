package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementLink;

import java.util.Optional;
import java.util.UUID;

public interface SettlementShareLinkStorePort {
    Optional<StoredSettlementShareLink> findBySettlementId(UUID settlementId);

    void replace(SettlementLink link, String rawToken);

    record StoredSettlementShareLink(
            SettlementLink link,
            String rawToken
    ) {
    }
}
