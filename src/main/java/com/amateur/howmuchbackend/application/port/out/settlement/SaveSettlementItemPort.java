package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

import java.util.List;
import java.util.UUID;

public interface SaveSettlementItemPort {
    List<SettlementItem> saveAll(List<SettlementItem> items);

    List<SettlementItem> replaceByExpenseId(UUID settlementId, UUID expenseId, List<SettlementItem> items);
}
