package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.UUID;

public interface NettingJobLauncherPort {
    void launch(UUID id, UUID leaseToken);
}
