package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;

import java.time.YearMonth;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LoadSettlementListPort {
    List<SettlementListReadModel> findPage(
            Long userId,
            SettlementListTab tab,
            SettlementListStatusFilter status,
            Long groupId,
            YearMonth yearMonth,
            LocalDateTime cursorRequestedAt,
            UUID cursorId,
            int limit
    );
}
