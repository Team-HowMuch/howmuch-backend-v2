package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;

import java.util.UUID;

public record SettlementParticipantCandidateWebResponse(
        String publicId,
        String displayName,
        String avatarUrl
) {
    public static SettlementParticipantCandidateWebResponse from(
            UUID settlementId,
            SettlementParticipantCandidateResult result
    ) {
        return new SettlementParticipantCandidateWebResponse(
                result.publicId(),
                result.displayName(),
                result.hasAvatar()
                        ? "/api/v2/settlements/%s/participant-candidates/users/%s/avatar".formatted(
                                settlementId,
                                result.publicId()
                        )
                        : null
        );
    }
}
