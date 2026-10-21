package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.user.model.ProfileImage;

import java.util.UUID;

public interface GetSettlementLinkParticipantAvatarUseCase {
    ProfileImage get(String token, UUID participantId);
}
