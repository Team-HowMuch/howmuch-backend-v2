package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementLink;

import java.util.Optional;

public interface LoadSettlementLinkPort {
    Optional<SettlementLink> findByTokenHash(String tokenHash);
}
