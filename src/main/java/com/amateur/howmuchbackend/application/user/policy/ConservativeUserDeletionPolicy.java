package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.domain.user.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ConservativeUserDeletionPolicy implements UserDeletionPolicy {
    @Override
    public Decision evaluate(Facts facts) {
        if (facts.hasActiveOwnerGroup()) {
            return new Decision(BlockReason.GROUP_OWNER_TRANSFER_REQUIRED);
        }
        if (facts.hasIncompleteSettlement()) {
            return new Decision(BlockReason.INCOMPLETE_SETTLEMENT);
        }
        return new Decision(BlockReason.NONE);
    }

    @Override
    public User anonymize(User user, String pinHash, LocalDateTime now) {
        String suffix = Long.toHexString(user.id());
        return user.deactivateForDeletion(
                "deleted-" + suffix,
                "del_" + suffix,
                "d-" + suffix,
                pinHash,
                now
        );
    }
}
