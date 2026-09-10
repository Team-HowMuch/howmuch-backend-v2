package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.YearMonth;

public record GetSettlementsCommand(
        SettlementListTab tab,
        SettlementListStatusFilter status,
        Long groupId,
        YearMonth yearMonth,
        String cursor,
        Integer size
) {
    public GetSettlementsCommand {
        tab = tab == null ? SettlementListTab.RECEIVE : tab;
        status = status == null ? SettlementListStatusFilter.IN_PROGRESS : status;
        size = size == null ? 20 : size;
        if (size < 1 || size > 50) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PAGE_SIZE);
        }
    }
}
