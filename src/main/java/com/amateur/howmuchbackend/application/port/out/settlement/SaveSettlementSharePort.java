package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

import java.util.List;

public interface SaveSettlementSharePort {
    void saveAll(List<SettlementShare> shares);
}
