package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.domain.user.User;

import java.time.LocalDateTime;

public interface UserDeletionPolicy {
    Decision evaluate(Facts facts);

    User anonymize(User user, String pinHash, LocalDateTime now);

    record Facts(boolean hasActiveOwnerGroup, boolean hasIncompleteSettlement) {
    }

    record Decision(BlockReason blockReason) {
        public boolean isAllowed() {
            return blockReason == BlockReason.NONE;
        }
    }

    enum BlockReason {
        NONE,
        GROUP_OWNER_TRANSFER_REQUIRED,
        INCOMPLETE_SETTLEMENT
    }
}
