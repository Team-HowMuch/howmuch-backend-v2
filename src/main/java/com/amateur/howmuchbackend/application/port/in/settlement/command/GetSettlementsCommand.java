package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.time.YearMonth;

public record GetSettlementsCommand(
        SettlementListTab tab,
        SettlementListStatusFilter status,
        Long groupId,
        YearMonth yearMonth
) {
    public GetSettlementsCommand {
        tab = tab == null ? SettlementListTab.RECEIVE : tab;
        status = status == null ? SettlementListStatusFilter.IN_PROGRESS : status;
    }
}
