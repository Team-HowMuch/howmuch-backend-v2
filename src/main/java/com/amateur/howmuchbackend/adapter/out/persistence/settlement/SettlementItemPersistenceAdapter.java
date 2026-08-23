package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementItemMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementItemPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementItemPersistenceAdapter implements
        LoadSettlementItemPort,
        SaveSettlementItemPort {
    private final SettlementItemJpaRepository settlementItemJpaRepository;

    @Override
    public List<SettlementItem> findAllBySettlementId(UUID settlementId) {
        return settlementItemJpaRepository.findAllOrderedBySettlementId(settlementId)
                .stream()
                .map(SettlementItemMapper::toDomain)
                .toList();
    }

    @Override
    public long totalAmount(UUID settlementId) {
        return settlementItemJpaRepository.sumAmountBySettlementId(settlementId);
    }

    @Override
    public boolean existsBySettlementId(UUID settlementId) {
        return settlementItemJpaRepository.existsBySettlementId(settlementId);
    }

    @Override
    public List<SettlementItem> saveAll(List<SettlementItem> items) {
        return settlementItemJpaRepository.saveAllAndFlush(items.stream()
                        .map(SettlementItemMapper::toEntity)
                        .toList())
                .stream()
                .map(SettlementItemMapper::toDomain)
                .toList();
    }
}
