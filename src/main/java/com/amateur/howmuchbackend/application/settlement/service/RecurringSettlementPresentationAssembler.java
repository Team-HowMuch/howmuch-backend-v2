package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPresentationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPresentationPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementPresentationAssembler {
    private final LoadRecurringSettlementPresentationPort presentation;
    private final CurrentActorPort actor;
    private final SettlementCategoryCatalog categories;

    public Map<UUID, RecurringSettlementPresentationResult> loadAll(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Long viewer = actor.getCurrentActor().userId();
        return presentation.loadAll(ids, viewer).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            var value = entry.getValue();
            var account = value.receivingAccount();
            return new RecurringSettlementPresentationResult(value.participants().stream().map(person -> {
                boolean self = person.userId().equals(viewer);
                String version = self || person.memberId() != null ? ImageCacheVersion.fromNullableFileName(person.avatarFileName()) : null;
                return new RecurringSettlementPresentationResult.Participant(person.userId(), person.name(), person.amount(),
                        person.displayOrder(), person.memberId(), version, self);
            }).toList(), new RecurringSettlementPresentationResult.ReceivingAccount(account.id(), account.bankName(), account.maskedNumber()));
        }));
    }

    public RecurringSettlementResult assemble(RecurringSettlement value) {
        return RecurringSettlementResult.from(value, loadAll(List.of(value.id())).get(value.id()), categories.byCode().get(value.categoryCode()));
    }
}
