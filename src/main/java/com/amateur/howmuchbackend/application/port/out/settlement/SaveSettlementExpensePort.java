package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

public interface SaveSettlementExpensePort {
    SettlementExpense save(SettlementExpense expense);

    SettlementExpense update(SettlementExpense expense);
}
