package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementItemMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemOptionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemOptionJpaEntity;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementItemPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementItemPersistenceAdapter implements
        LoadSettlementItemPort,
        SaveSettlementItemPort {
    private final SettlementItemJpaRepository settlementItemJpaRepository;
    private final SettlementItemOptionJpaRepository optionRepository;

    @Override
    public List<SettlementItem> findAllBySettlementId(UUID settlementId) {
        var entities = settlementItemJpaRepository.findAllOrderedBySettlementId(settlementId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SettlementItemOption>> optionsByItemId = optionRepository
                .findBySettlementIdAndItemIdInOrderByItemIdAscDisplayOrderAsc(
                        settlementId, entities.stream().map(entity -> entity.getId()).toList()
                ).stream()
                .map(entity -> new SettlementItemOption(
                        entity.getId(), entity.getSettlementId(), entity.getItemId(),
                        entity.getName(), entity.getPrice(), entity.getDisplayOrder()
                ))
                .collect(Collectors.groupingBy(SettlementItemOption::itemId));
        return entities
                .stream()
                .map(entity -> SettlementItemMapper.toDomain(
                        entity, optionsByItemId.getOrDefault(entity.getId(), List.of())
                ))
                .toList();
    }

    @Override
    public List<SettlementItem> saveAll(List<SettlementItem> items) {
        var saved = settlementItemJpaRepository.saveAllAndFlush(items.stream()
                        .map(SettlementItemMapper::toEntity)
                        .toList());
        optionRepository.saveAllAndFlush(items.stream()
                .flatMap(item -> item.options().stream())
                .map(SettlementItemOptionJpaEntity::new)
                .toList());
        Map<UUID, SettlementItem> itemById = items.stream()
                .collect(Collectors.toMap(SettlementItem::id, item -> item));
        return saved.stream()
                .map(entity -> SettlementItemMapper.toDomain(
                        entity, itemById.get(entity.getId()).options()
                )).toList();
    }
}
