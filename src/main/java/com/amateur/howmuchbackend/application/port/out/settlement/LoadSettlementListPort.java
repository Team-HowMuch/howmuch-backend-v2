package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;

import java.util.List;

import java.time.YearMonth;

public interface LoadSettlementListPort {
    List<SettlementListReadModel> findAll(
            Long userId,
            SettlementListTab tab,
            SettlementListStatusFilter status,
            Long groupId,
            YearMonth yearMonth
    );
}
