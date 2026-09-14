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
                result.avatarVersion() == null
                        ? null
                        : "/api/v2/settlements/%s/participant-candidates/users/%s/avatar?v=%s".formatted(
                                settlementId,
                                result.publicId(),
                                result.avatarVersion()
                        )
        );
    }
}
