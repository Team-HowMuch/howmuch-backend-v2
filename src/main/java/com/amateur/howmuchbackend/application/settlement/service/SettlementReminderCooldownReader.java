package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementReminderCooldownReader {
    private final SettlementReminderCooldownPort cooldownPort;
    private final Clock clock;

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
