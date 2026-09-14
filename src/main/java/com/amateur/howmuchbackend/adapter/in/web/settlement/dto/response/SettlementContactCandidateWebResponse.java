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
                result.avatarVersion() == null
                        ? null
                        : "/api/v2/settlements/%s/participant-candidates/users/%s/avatar?v=%s".formatted(
                                settlementId,
                                result.publicId(),
                                result.avatarVersion()
                        ),
                result.alreadyParticipant()
        );
    }
}
