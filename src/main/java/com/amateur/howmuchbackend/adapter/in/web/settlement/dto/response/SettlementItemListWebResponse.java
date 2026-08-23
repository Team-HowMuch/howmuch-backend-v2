package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;

import java.util.List;

public record SettlementItemListWebResponse(
        List<SettlementItemWebResponse> items,
        long totalItemAmount
) {
    public static SettlementItemListWebResponse from(SettlementItemListResult result) {
        return new SettlementItemListWebResponse(
                result.items().stream().map(SettlementItemWebResponse::from).toList(),
                result.totalItemAmount()
        );
    }
}
