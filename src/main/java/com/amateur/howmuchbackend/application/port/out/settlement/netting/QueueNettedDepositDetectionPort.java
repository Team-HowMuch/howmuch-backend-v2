package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.time.LocalDateTime;

public interface QueueNettedDepositDetectionPort {
    void observed(long accountId, LocalDateTime now);
}
