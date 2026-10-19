package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReminderFactsPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementReminderCooldownReader {
    private final SettlementReminderCooldownPort cooldownPort;
    private final Clock clock;
    private final LoadSettlementReminderFactsPort reminderFactsPort;
    private final SettlementReminderPolicy reminderPolicy;

    public Map<UUID, LocalDateTime> findLatestNextAvailableAt(Map<UUID, SettlementStatus> settlements) {
        List<UUID> settlementIds = settlements.entrySet().stream()
                .filter(entry -> reminderPolicy.supportsManualReminder(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        var factsBySettlement = reminderFactsPort.findAllBySettlementIds(settlementIds).stream()
                .collect(Collectors.groupingBy(LoadSettlementReminderFactsPort.ParticipantFacts::settlementId));
        Map<UUID, LocalDateTime> nextAvailableAt = new HashMap<>();
        for (UUID settlementId : settlementIds) {
            List<UUID> candidateIds = factsBySettlement.getOrDefault(settlementId, List.of()).stream()
                    .filter(fact -> reminderPolicy.isManualReminderCandidate(
                            settlements.get(settlementId), fact.linkedNonCreator(), fact.submitted(),
                            fact.sharePresent() && fact.confirmedAmount() == 0))
                    .map(LoadSettlementReminderFactsPort.ParticipantFacts::participantId)
                    .toList();
            findLatestNextAvailableAt(settlementId, candidateIds)
                    .ifPresent(time -> nextAvailableAt.put(settlementId, time));
        }
        return Map.copyOf(nextAvailableAt);
    }

    public Optional<LocalDateTime> findLatestNextAvailableAt(
            UUID settlementId,
            Collection<UUID> participantIds
    ) {
        return participantIds.stream()
                .distinct()
                .map(participantId -> cooldownPort.findRemainingCooldown(
                        settlementId,
                        participantId
                ))
                .flatMap(Optional::stream)
                .filter(duration -> !duration.isNegative() && !duration.isZero())
                .map(duration -> LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                        .plus(duration))
                .max(Comparator.naturalOrder());
    }
}
