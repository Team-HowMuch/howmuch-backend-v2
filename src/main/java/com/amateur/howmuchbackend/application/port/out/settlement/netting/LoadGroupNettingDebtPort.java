package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadGroupNettingDebtPort {
    List<NettingInput.Source> findAwaitingSources(long groupId);

    Optional<SourceIdentity> findAwaitingSourceIdentity(long groupId, UUID settlementId);

    record SourceIdentity(
            UUID settlementId, long receivingAccountId, String receivingAccountDigest) {}

    long currentVersion(long groupId);

    boolean isReceivingAccountConnected(long accountId);

    /** Lock existing users, connections, accounts and original settlements in that order. */
    void lockInputs(NettingInput input);

    void lockPaymentInputs(LoadGroupNettingPort.Netting netting);

    boolean hasOpenReviews(long groupId);
}
