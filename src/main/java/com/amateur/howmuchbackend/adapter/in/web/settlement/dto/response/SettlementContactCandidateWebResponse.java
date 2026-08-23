package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementContactCandidateResult;

import java.util.UUID;

public record SettlementContactCandidateWebResponse(
        String contactId,
        String contactDisplayName,
        boolean member,
        String publicId,
        String memberDisplayName,
        String avatarUrl,
        boolean alreadyParticipant
) {
    public static SettlementContactCandidateWebResponse from(
            UUID settlementId,
            SettlementContactCandidateResult result
    ) {
        return new SettlementContactCandidateWebResponse(
                result.contactId(),
                result.contactDisplayName(),
                result.member(),
                result.publicId(),
                result.memberDisplayName(),
                result.hasAvatar()
                        ? "/api/v2/settlements/%s/participant-candidates/users/%s/avatar".formatted(
                                settlementId,
                                result.publicId()
                        )
                        : null,
                result.alreadyParticipant()
        );
    }
}
