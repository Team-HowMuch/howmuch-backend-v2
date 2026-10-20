package com.amateur.howmuchbackend.application.port.in.settlement;

import java.util.UUID;

public interface DeleteRecurringSettlementUseCase {
    void delete(Long groupId, UUID id);
}
