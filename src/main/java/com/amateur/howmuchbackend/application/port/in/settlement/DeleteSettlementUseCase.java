package com.amateur.howmuchbackend.application.port.in.settlement;

import java.util.UUID;

public interface DeleteSettlementUseCase {
    void delete(UUID settlementId);
}
