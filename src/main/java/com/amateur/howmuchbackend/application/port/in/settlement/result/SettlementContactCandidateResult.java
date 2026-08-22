package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.user.User;

public record SettlementContactCandidateResult(
        String contactId,
        String contactDisplayName,
        boolean member,
        String publicId,
        String memberDisplayName,
        boolean hasAvatar,
        boolean alreadyParticipant
) {
    public static SettlementContactCandidateResult of(
            String contactId,
            String contactDisplayName,
            User matchedUser,
            boolean alreadyParticipant
    ) {
        return new SettlementContactCandidateResult(
                contactId,
                contactDisplayName,
                matchedUser != null,
                matchedUser == null ? null : matchedUser.publicId(),
                matchedUser == null ? null : matchedUser.name(),
                matchedUser != null
                        && matchedUser.avatarFileName() != null
                        && !alreadyParticipant,
                alreadyParticipant
        );
    }
}
