package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record GetSettlementsCommand(
        SettlementListTab tab,
        SettlementListStatusFilter status,
        Long groupId
) {
    public GetSettlementsCommand {
        tab = tab == null ? SettlementListTab.RECEIVE : tab;
        status = status == null ? SettlementListStatusFilter.IN_PROGRESS : status;
    }
}
