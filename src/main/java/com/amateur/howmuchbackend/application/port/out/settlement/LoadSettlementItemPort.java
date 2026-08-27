package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;
import java.util.UUID;

public interface LoadSettlementItemPort {
    List<SettlementItem> findAllBySettlementId(UUID settlementId);
}
