package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;

public record SelectSettlementAllocationTypeCommand(
        SettlementAllocationType allocationType
) {
}
