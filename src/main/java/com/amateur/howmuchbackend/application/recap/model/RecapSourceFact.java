package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;
import java.util.UUID;

/** One completed settlement joined to one companion; own amount precedes the join. */
public record RecapSourceFact(
        UUID settlementId, LocalDateTime completedAt, Long ownAmount, boolean validZeroSubmission,
        UUID participantId, Long linkedUserId, String participantName,
        String accountName, String publicId, String avatarFileName, boolean accountActive
) {}
