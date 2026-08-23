package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

public interface DeleteSettlementExpensePort {
    void delete(SettlementExpense expense);
}
