package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemOptionResult;

public record SettlementItemOptionWebResponse(String name, long price) {
    public static SettlementItemOptionWebResponse from(SettlementItemOptionResult result) {
        return new SettlementItemOptionWebResponse(result.name(), result.price());
    }
}
