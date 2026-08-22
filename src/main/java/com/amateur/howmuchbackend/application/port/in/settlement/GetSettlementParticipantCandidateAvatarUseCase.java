package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.user.model.ProfileImage;

import java.util.UUID;

public interface GetSettlementParticipantCandidateAvatarUseCase {
    ProfileImage get(UUID settlementId, String publicId);
}
