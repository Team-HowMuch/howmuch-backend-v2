package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementWidgetSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementWidgetPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetRefreshStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementWidgetSummaryPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementWidgetSummaryService implements GetSettlementWidgetSummaryUseCase {
    private final CurrentActorPort actorPort;
    private final LoadSettlementWidgetPort widgetPort;
    private final SettlementWidgetRefreshStorePort refreshStore;
    private final LoadSettlementExpensePort expensePort;
    private final LoadSettlementItemPort itemPort;
    private final SettlementItemAllocationStorePort allocationPort;
    private final SettlementItemAllocationPolicy allocationPolicy;
    private final SettlementWidgetSummaryPolicy summaryPolicy;

    @Override
    @Transactional(readOnly = true)
    public SettlementWidgetSummaryResult getSummary() {
        Long userId = actorPort.getCurrentActor().userId();
        var facts = widgetPort.findAllInProgress(userId);
        Map<UUID, Long> requested = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (var fact : facts) {
            if (!fact.creator() || fact.status() != SettlementStatus.REQUESTED
                    || fact.allocationType() != SettlementAllocationType.ITEM_ALLOCATION
                    || !seen.add(fact.settlementId())) continue;
            long amount = fact.totalAmount();
            if (fact.creatorParticipantId() != null) {
                var expenses = expensePort.findAllBySettlementId(fact.settlementId());
                amount = allocationPolicy.calculateRequestedReceivableAmount(amount,
                        itemPort.findAllBySettlementId(fact.settlementId()),
                        allocationPort.findAllSelections(fact.settlementId()), fact.creatorParticipantId(),
                        expenses.stream().mapToLong(SettlementExpense::discount).reduce(0L, Math::addExact),
                        expenses.stream().mapToLong(SettlementExpense::deliveryFee).reduce(0L, Math::addExact));
            }
            requested.put(fact.settlementId(), amount);
        }
        return summaryPolicy.summarize(facts, requested, refreshStore.lastReconciledAt(userId).orElse(null));
    }
}
