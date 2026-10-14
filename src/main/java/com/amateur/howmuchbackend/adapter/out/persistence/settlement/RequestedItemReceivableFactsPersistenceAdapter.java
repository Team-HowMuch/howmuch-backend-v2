package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementExpenseJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemSelectionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementExpenseMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementItemAllocationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementItemMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementExpenseJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemOptionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemSelectionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 정산마다 따로 읽던 품목·옵션·선택·지출·생성자 참여자를 정산 ID 목록으로 한 번씩만 읽는다.
 * 쿼리 수는 정산 건수와 무관하게 여섯 번이다.
 *
 * <p>도메인 객체로 바꾸는 단계도 정산별로 나눈다. 저장된 행이 도메인 규칙과 맞지 않는 정산 한 건 때문에
 * 정산 목록이나 AI 비서 전체가 실패하지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestedItemReceivableFactsPersistenceAdapter implements LoadRequestedItemReceivableFactsPort {
    private final SettlementJpaRepository settlementJpaRepository;
    private final SettlementParticipantJpaRepository participantRepository;
    private final SettlementItemJpaRepository itemRepository;
    private final SettlementItemOptionJpaRepository optionRepository;
    private final SettlementItemSelectionJpaRepository selectionRepository;
    private final SettlementExpenseJpaRepository expenseRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RequestedItemReceivableFacts> findAllBySettlementIds(Collection<UUID> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = settlementIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, SettlementJpaEntity> settlements = settlementJpaRepository.findAllById(ids).stream()
                .filter(settlement -> settlement.getDeletedAt() == null)
                .collect(Collectors.toMap(SettlementJpaEntity::getId, Function.identity()));
        if (settlements.isEmpty()) {
            return List.of();
        }
        List<UUID> liveIds = ids.stream().filter(settlements::containsKey).toList();

        Map<UUID, UUID> creatorParticipantIds = creatorParticipantIds(liveIds, settlements);
        Map<UUID, List<SettlementItemJpaEntity>> items = itemRepository.findAllOrderedBySettlementIdIn(liveIds)
                .stream()
                .collect(Collectors.groupingBy(SettlementItemJpaEntity::getSettlementId));
        Map<UUID, List<SettlementItemOption>> optionsByItemId = items.isEmpty()
                ? Map.of()
                : optionRepository.findBySettlementIdInOrderByItemIdAscDisplayOrderAsc(liveIds)
                        .stream()
                        .map(entity -> new SettlementItemOption(
                                entity.getId(), entity.getSettlementId(), entity.getItemId(),
                                entity.getName(), entity.getPrice(), entity.getDisplayOrder()
                        ))
                        .collect(Collectors.groupingBy(SettlementItemOption::itemId));
        Map<UUID, List<SettlementItemSelectionJpaEntity>> selections = selectionRepository
                .findAllBySettlementIdIn(liveIds)
                .stream()
                .collect(Collectors.groupingBy(SettlementItemSelectionJpaEntity::getSettlementId));
        Map<UUID, List<SettlementExpenseJpaEntity>> expenses = expenseRepository
                .findAllBySettlementIdInOrderByDisplayOrderAsc(liveIds)
                .stream()
                .collect(Collectors.groupingBy(SettlementExpenseJpaEntity::getSettlementId));

        List<RequestedItemReceivableFacts> facts = new ArrayList<>();
        for (UUID id : liveIds) {
            try {
                facts.add(new RequestedItemReceivableFacts(
                        id,
                        settlements.get(id).getTotalAmount(),
                        creatorParticipantIds.get(id),
                        items.getOrDefault(id, List.of()).stream()
                                .map(entity -> SettlementItemMapper.toDomain(
                                        entity, optionsByItemId.getOrDefault(entity.getId(), List.of())))
                                .toList(),
                        selections.getOrDefault(id, List.of()).stream()
                                .map(SettlementItemAllocationMapper::toDomain)
                                .toList(),
                        expenses.getOrDefault(id, List.of()).stream()
                                .map(SettlementExpenseMapper::toDomain)
                                .toList()
                ));
            } catch (ApplicationException | IllegalArgumentException | ArithmeticException exception) {
                // 저장된 행이 도메인 규칙과 맞지 않는 정산 한 건은 빼고, 호출자가 Share 합계로 대체하게 한다.
                log.warn(
                        "Skipped requested item receivable facts. settlementId={} failureType={}",
                        id,
                        exception.getClass().getSimpleName()
                );
            }
        }
        return facts;
    }

    /** 정산 상세와 같이 생성자에게 연결된 참여자 중 표시 순서가 가장 앞선 사람을 쓴다. */
    private Map<UUID, UUID> creatorParticipantIds(List<UUID> ids, Map<UUID, SettlementJpaEntity> settlements) {
        Map<UUID, UUID> creatorParticipantIds = new HashMap<>();
        for (SettlementParticipantJpaEntity participant
                : participantRepository.findAllBySettlementIdInOrderByDisplayOrderAsc(ids)) {
            Long creatorId = settlements.get(participant.getSettlementId()).getCreatorId();
            if (creatorId != null && creatorId.equals(participant.getLinkedUserId())) {
                creatorParticipantIds.putIfAbsent(participant.getSettlementId(), participant.getId());
            }
        }
        return creatorParticipantIds;
    }
}
