package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemOption;

public record SettlementItemOptionResult(String name, long price) {
    public static SettlementItemOptionResult from(SettlementItemOption option) {
        return new SettlementItemOptionResult(option.name(), option.price());
    }
}
