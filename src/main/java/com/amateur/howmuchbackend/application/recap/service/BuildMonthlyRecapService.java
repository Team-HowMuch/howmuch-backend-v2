package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.in.recap.BuildMonthlyRecapUseCase;
import com.amateur.howmuchbackend.application.port.out.recap.LoadMonthlyRecapPort;
import com.amateur.howmuchbackend.application.port.out.recap.LoadRecapSourcePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import com.amateur.howmuchbackend.application.recap.model.RecapCategory;
import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import com.amateur.howmuchbackend.application.recap.policy.RecapCardPolicy;
import com.amateur.howmuchbackend.application.settlement.service.MySettlementCategoryReader;
import com.amateur.howmuchbackend.application.recap.policy.RecapComparisonPolicy;
import com.amateur.howmuchbackend.application.recap.policy.RecapCompanionPolicy;
import com.amateur.howmuchbackend.application.recap.policy.RecapSummaryPolicy;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BuildMonthlyRecapService implements BuildMonthlyRecapUseCase {
    private final LoadUserPort users;
    private final LoadRecapSourcePort source;
    private final LoadMonthlyRecapPort snapshots;
    private final RecapSummaryPolicy summaryPolicy;
    private final com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy allocationPolicy;
    private final RecapComparisonPolicy comparisonPolicy;
    private final RecapCompanionPolicy companionPolicy;
    private final MySettlementCategoryReader categoryReader;
    private final RecapCardPolicy cardPolicy;

    @Override
    @Transactional(readOnly = true)
    public Optional<MonthlyRecap> build(Long ownerId, YearMonth month, LocalDateTime now) {
        var owner = users.findById(ownerId).filter(User::active);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var facts = source.findCompletedMonth(ownerId, month);
        java.util.Map<UUID, Boolean> zeroBySettlement = new java.util.HashMap<>();
        facts = facts.stream().map(fact -> {
            if (fact.ownAmount() != null || fact.allocation() == null) return fact;
            boolean confirmed = zeroBySettlement.computeIfAbsent(fact.settlementId(), ignored -> {
                var a = fact.allocation();
                var calculation = allocationPolicy.finalizeAllocation(fact.settlementId(), a.settlementTotalAmount(),
                        a.participants(), a.items(), a.selections(), now, a.receiptDiscount(), a.deliveryFee());
                return summaryPolicy.isConfirmedZero(fact, calculation.valid(), calculation.shares());
            });
            return fact.withConfirmedZero(confirmed);
        }).toList();
        var summary = summaryPolicy.summarize(facts);
        if (summary.completedSettlementCount() == 0) {
            return Optional.empty();
        }
        Long previous = snapshots.find(ownerId, month.minusMonths(1))
                .map(recap -> recap.summary().totalOwnAmount()).orElse(null);
        var settlementIds = facts.stream().map(RecapSourceFact::settlementId).distinct().toList();
        java.util.Map<UUID, RecapCategory> categories = new java.util.HashMap<>();
        categoryReader.read(ownerId, settlementIds).forEach((id, category) ->
                categories.put(id, new RecapCategory(category.code(), category.name(), category.icon())));
        var cards = cardPolicy.summarize(facts, categories);
        return Optional.of(new MonthlyRecap(UUID.randomUUID(), ownerId, month, owner.get().name(), summary,
                comparisonPolicy.compare(summary.totalOwnAmount(), previous),
                companionPolicy.select(ownerId, facts), now.truncatedTo(ChronoUnit.MICROS),
                cards.categories(), cards.record(), null));
    }
}
