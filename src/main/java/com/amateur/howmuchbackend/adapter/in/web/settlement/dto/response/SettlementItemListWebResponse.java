package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SettlementItemListWebResponse(
        List<SettlementItemWebResponse> items,
        long totalItemAmount,
        long discount,
        @Schema(description = "영수증 순 배달비. 배달비가 없으면 0원", minimum = "0")
        long deliveryFee
) {
    public static SettlementItemListWebResponse from(SettlementItemListResult result) {
        return new SettlementItemListWebResponse(
                result.items().stream().map(SettlementItemWebResponse::from).toList(),
                result.totalItemAmount(),
                result.discount(),
                result.deliveryFee()
        );
    }
}
