package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TreasurerSettlementQuery {
    private final CurrentActorPort currentActorPort;
    private final LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;

    public List<PersonalSettlement> loadMine() {
        Long userId = currentActorPort.getCurrentActor().userId();
        return loadTreasurerSettlementsPort.findAll(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    private PersonalSettlement toDomain(LoadTreasurerSettlementsPort.TreasurerSettlementRecord record) {
        return new PersonalSettlement(
                record.settlementId(),
                record.title(),
                record.direction(),
                record.status(),
                record.requestedAt(),
                record.amount(),
                record.counterpartName(),
                record.payerCount(),
                record.completedPayerCount(),
                record.allocationType()
        );
    }
}
