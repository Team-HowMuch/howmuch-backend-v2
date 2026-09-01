package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.common.PostgresLockTimeoutConfigurer;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReceivingAccountUsagePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementPersistenceAdapter implements
        LoadSettlementPort,
        LoadSettlementListPort,
        SaveSettlementPort,
        DeleteSettlementPort,
        SettlementReceivingAccountUsagePort {
    private final SettlementJpaRepository settlementJpaRepository;
    private final PostgresLockTimeoutConfigurer lockTimeoutConfigurer;

    @Override
    public Settlement save(Settlement settlement) {
        return SettlementMapper.toDomain(
                settlementJpaRepository.save(SettlementMapper.toEntity(settlement))
        );
    }

    @Override
    public Optional<Settlement> findById(UUID settlementId) {
        return settlementJpaRepository.findById(settlementId).map(SettlementMapper::toDomain);
    }

    @Override
    public Optional<Settlement> findByIdForUpdate(UUID settlementId) {
        lockTimeoutConfigurer.apply();
        return settlementJpaRepository.findByIdForUpdate(settlementId).map(SettlementMapper::toDomain);
    }

    @Override
    public List<SettlementListReadModel> findAll(
            Long userId,
            SettlementListTab tab,
            SettlementListStatusFilter status,
            Long groupId
    ) {
        var projections = settlementJpaRepository.findAllForUser(userId, tab.name(), status.name(), groupId);
        if (projections.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SettlementListReadModel.GroupSummary>> groupsBySettlementId =
                settlementJpaRepository.findGroupsForSettlements(
                        projections.stream()
                                .map(SettlementJpaRepository.SettlementListProjection::getSettlementId)
                                .toList(),
                        userId
                ).stream()
                        .collect(Collectors.groupingBy(
                                SettlementJpaRepository.SettlementListGroupProjection::getSettlementId,
                                Collectors.mapping(
                                        projection -> new SettlementListReadModel.GroupSummary(
                                                projection.getGroupId(),
                                                projection.getGroupName(),
                                                projection.getImageFileName() != null
                                        ),
                                        Collectors.toList()
                                )
                        ));
        return projections.stream()
                .map(projection -> toReadModel(
                        projection,
                        tab,
                        groupsBySettlementId.getOrDefault(projection.getSettlementId(), List.of())
                ))
                .toList();
    }

    @Override
    public Settlement update(Settlement settlement) {
        var entity = settlementJpaRepository.findById(settlement.id())
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        entity.updateFrom(settlement);
        return SettlementMapper.toDomain(settlementJpaRepository.save(entity));
    }

    @Override
    public void delete(UUID settlementId) {
        settlementJpaRepository.deleteById(settlementId);
    }

    private SettlementListReadModel toReadModel(
            SettlementJpaRepository.SettlementListProjection projection,
            SettlementListTab tab,
            List<SettlementListReadModel.GroupSummary> groups
    ) {
        SettlementStatus status = SettlementStatus.valueOf(projection.getStatus());
        int completedParticipantCount = projection.getCompletedParticipantCount() == null
                ? 0
                : Math.toIntExact(projection.getCompletedParticipantCount());
        // Share가 없으면 납부 대상도 아직 정해지지 않았다. completedParticipantCount와 같은 기준을 쓴다.
        int payableParticipantCount = projection.getPayableParticipantCount() == null
                ? 0
                : Math.toIntExact(projection.getPayableParticipantCount());
        return new SettlementListReadModel(
                projection.getSettlementId(),
                status,
                projection.getRequestedAt(),
                tab == SettlementListTab.RECEIVE ? projection.getTotalAmount() : null,
                tab == SettlementListTab.RECEIVE
                        ? Math.toIntExact(projection.getParticipantCount())
                        : null,
                tab == SettlementListTab.RECEIVE ? payableParticipantCount : null,
                tab == SettlementListTab.RECEIVE ? completedParticipantCount : null,
                tab == SettlementListTab.PAY ? projection.getAmountToPay() : null,
                tab == SettlementListTab.PAY ? projection.getRequesterName() : null,
                groups
        );
    }

    @Override
    public boolean existsDraftByReceivingAccountId(Long accountId) {
        return settlementJpaRepository.existsByReceivingAccountIdAndStatus(accountId, SettlementStatus.DRAFT);
    }

    @Override
    public void detachIssuedByReceivingAccountId(Long accountId) {
        settlementJpaRepository.detachIssuedByReceivingAccountId(accountId, SettlementStatus.DRAFT);
    }
}
