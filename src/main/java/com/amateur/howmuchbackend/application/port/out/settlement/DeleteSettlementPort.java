package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

public interface DeleteSettlementPort {
    void delete(UUID settlementId, LocalDateTime deletedAt);
}
