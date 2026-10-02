package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record SettlementItemAllocationWebResponse(
        UUID settlementId,
        UUID participantId,
        String participantDisplayName,
        SettlementItemAllocationPhase phase,
        long personalAmount,
        int submittedParticipantCount,
        int totalParticipantCount,
        List<SettlementItemAllocationItemWebResponse> items,
        List<UUID> invalidItemIds,
        long discount,
        long discountShare,
        @Schema(description = "영수증 전체 순 배달비 합계", minimum = "0")
        long deliveryFee,
        @Schema(description = "품목 순액 비율로 배분한 배달비. 전체 제출 전 잠정치이며 배분 확정 후 원 단위 잔액을 포함합니다.", minimum = "0")
        long deliveryFeeShare
) {
    public static SettlementItemAllocationWebResponse from(
            SettlementItemAllocationResult result
    ) {
        return new SettlementItemAllocationWebResponse(
                result.settlementId(),
                result.participantId(),
                result.participantDisplayName(),
                result.phase(),
                result.personalAmount(),
                result.submittedParticipantCount(),
                result.totalParticipantCount(),
                result.items().stream()
                        .map(SettlementItemAllocationItemWebResponse::from)
                        .toList(),
                result.invalidItemIds(),
                result.discount(),
                result.discountShare(),
                result.deliveryFee(),
                result.deliveryFeeShare()
        );
    }
}
