package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TreasurerSettlementPersistenceAdapter implements LoadTreasurerSettlementsPort {
    private final SettlementJpaRepository settlementJpaRepository;

    @Override
    public List<TreasurerSettlementRecord> findAll(Long userId) {
        List<TreasurerSettlementRecord> records = new ArrayList<>();
        records.addAll(map(settlementJpaRepository.findAllForTreasurer(userId, SettlementListTab.RECEIVE.name()), SettlementListTab.RECEIVE));
        records.addAll(map(settlementJpaRepository.findAllForTreasurer(userId, SettlementListTab.PAY.name()), SettlementListTab.PAY));
        return List.copyOf(records);
    }

    private List<TreasurerSettlementRecord> map(
            List<SettlementJpaRepository.TreasurerSettlementProjection> projections,
            SettlementListTab tab
    ) {
        return projections.stream()
                .map(projection -> new TreasurerSettlementRecord(
                        projection.getSettlementId(),
                        projection.getTitle(),
                        tab,
                        SettlementStatus.valueOf(projection.getStatus()),
                        projection.getRequestedAt(),
                        tab == SettlementListTab.PAY
                                ? projection.getAmountToPay()
                                : projection.getReceivableAmount(),
                        projection.getRequesterName()
                ))
                .toList();
    }
}
