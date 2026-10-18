package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.application.account.model.AccountCandidate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Short-lived cache of the account candidate list shown during bank linking. It removes repeated
 * CODEF account-list calls when the user re-enters the selection screen after connecting.
 *
 * <p>Only the client-visible candidate shape is cached. The final link still calls CODEF and
 * revalidates the selected candidate keys, so a stale cache cannot create an invalid account.
 */
public interface BankAccountCandidateCachePort {
    Optional<List<AccountCandidate>> find(Long userId, Long connectionId, String organization);

    void save(
            Long userId,
            Long connectionId,
            String organization,
            List<AccountCandidate> candidates,
            Duration ttl
    );

    void evict(Long userId, Long connectionId, String organization);
}
