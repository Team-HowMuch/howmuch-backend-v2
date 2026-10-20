package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;
import java.util.UUID;

/** One completed settlement joined to one companion; own amount precedes the join. */
public record RecapSourceFact(
        UUID settlementId, LocalDateTime completedAt, Long ownAmount, boolean validZeroSubmission,
        UUID participantId, Long linkedUserId, String participantName,
        String accountName, String publicId, String avatarFileName, boolean accountActive, RecapAllocationFacts allocation,
        LocalDateTime requestedAt, String title
) {
    public RecapSourceFact(UUID settlementId, LocalDateTime completedAt, Long ownAmount, boolean validZeroSubmission,
            UUID participantId, Long linkedUserId, String participantName, String accountName,
            String publicId, String avatarFileName, boolean accountActive) {
        this(settlementId, completedAt, ownAmount, validZeroSubmission, participantId, linkedUserId,
                participantName, accountName, publicId, avatarFileName, accountActive, null, null, null);
    }

    public RecapSourceFact withConfirmedZero(boolean confirmed) {
        return new RecapSourceFact(settlementId, completedAt, ownAmount, confirmed, participantId,
                linkedUserId, participantName, accountName, publicId, avatarFileName, accountActive, allocation,
                requestedAt, title);
    }

    public long confirmedOwnAmount() {
        if (ownAmount == null && !validZeroSubmission) {
            throw new IllegalStateException("Incomplete own share in completed settlement");
        }
        long amount = ownAmount == null ? 0 : ownAmount;
        if (amount < 0) {
            throw new IllegalStateException("Negative own share");
        }
        return amount;
    }
}
