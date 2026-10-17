package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.UUID;

public interface NettingTransferAvailabilityPort {
    boolean isOriginalReplacementPending(UUID settlementId);

    boolean isGroupReplacementPending(long groupId);
}
