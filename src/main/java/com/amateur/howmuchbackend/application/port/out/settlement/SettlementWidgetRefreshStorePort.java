package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SettlementWidgetRefreshStorePort {
    boolean tryAcquire(Long userId, String owner);
    boolean renew(Long userId, String owner);
    void finish(Long userId, String owner, LocalDateTime successfulReconciledAt);
    Optional<LocalDateTime> lastReconciledAt(Long userId);
}
