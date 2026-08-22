package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.user.User;

public record SettlementParticipantCandidateResult(
        String publicId,
        String displayName,
        boolean hasAvatar
) {
    public static SettlementParticipantCandidateResult from(User user) {
        return new SettlementParticipantCandidateResult(
                user.publicId(),
                user.name(),
                user.avatarFileName() != null
        );
    }
}
