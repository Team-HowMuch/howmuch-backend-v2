package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantResult;

import java.util.UUID;

public record SettlementLinkParticipantWebResponse(
        UUID participantId,
        String displayName,
        boolean isRequester,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true,
                description = "입금 대기 중 더치페이의 확정 금액. 납부 의무가 없으면 0이며 미확정 또는 품목 정산이면 null이다.")
        Long amount
) {
    public static SettlementLinkParticipantWebResponse from(
            SettlementLinkParticipantResult result
    ) {
        return new SettlementLinkParticipantWebResponse(
                result.participantId(),
                result.displayName(),
                result.isRequester(),
                result.amount()
        );
    }
}
