package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementExpensePort {
    List<SettlementExpense> findAllBySettlementId(UUID settlementId);

    Optional<SettlementExpense> findBySettlementIdAndId(UUID settlementId, UUID expenseId);

    int nextDisplayOrder(UUID settlementId);

    long totalAmount(UUID settlementId);

    boolean existsBySettlementIdAndSourceAndSourceReference(
            UUID settlementId,
            SettlementExpenseSource source,
            String sourceReference
    );
}
