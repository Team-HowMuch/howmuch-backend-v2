package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.application.settlement.policy.SettlementContactMatchUsage;

import java.time.Duration;

public interface TrackSettlementContactMatchUsagePort {
    SettlementContactMatchUsage recordUsage(
            Long userId,
            int contactCount,
            Duration requestWindow,
            Duration contactWindow
    );
}
