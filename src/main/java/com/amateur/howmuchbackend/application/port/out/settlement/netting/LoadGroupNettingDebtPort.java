package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.List;

public interface LoadGroupNettingDebtPort {
    List<NettingInput.Source> findAwaitingSources(long groupId);

    long currentVersion(long groupId);

    boolean isReceivingAccountConnected(long accountId);

    /** Lock existing users, connections, accounts and original settlements in that order. */
    void lockInputs(NettingInput input);

    void lockPaymentInputs(LoadGroupNettingPort.Netting netting);

    boolean hasOpenReviews(long groupId);
}
