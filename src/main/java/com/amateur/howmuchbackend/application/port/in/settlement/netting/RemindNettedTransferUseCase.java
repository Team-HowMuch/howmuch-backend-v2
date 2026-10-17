package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import java.util.UUID;

public interface RemindNettedTransferUseCase {
    void remind(long groupId, UUID nettingId, UUID transferId);

    void remindAutomatically();
}
