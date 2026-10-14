package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;

public interface SettlementBankLookupBudgetPort {
    void reserve(Long userId, boolean automatic, LocalDateTime now, int totalLimit, int automaticLimit);
}
