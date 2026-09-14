package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.domain.user.User;

public record SettlementParticipantCandidateResult(
        String publicId,
        String displayName,
        String avatarVersion
) {
    public static SettlementParticipantCandidateResult from(User user) {
        return new SettlementParticipantCandidateResult(
                user.publicId(),
                user.name(),
                ImageCacheVersion.fromNullableFileName(user.avatarFileName())
        );
    }
}
