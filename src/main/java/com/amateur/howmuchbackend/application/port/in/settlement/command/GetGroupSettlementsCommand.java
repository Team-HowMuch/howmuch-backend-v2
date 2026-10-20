package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record GetGroupSettlementsCommand(Long groupId, SettlementListStatusFilter status) {
    public GetGroupSettlementsCommand {
        status = status == null ? SettlementListStatusFilter.IN_PROGRESS : status;
    }
}
