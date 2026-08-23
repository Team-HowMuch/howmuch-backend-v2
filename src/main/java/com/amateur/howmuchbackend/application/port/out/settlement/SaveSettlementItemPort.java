package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;

public interface SaveSettlementItemPort {
    List<SettlementItem> saveAll(List<SettlementItem> items);
}
