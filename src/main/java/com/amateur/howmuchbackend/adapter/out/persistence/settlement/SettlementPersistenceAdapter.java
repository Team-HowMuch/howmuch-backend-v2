package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

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

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementPersistenceAdapter implements
        LoadSettlementPort,
        LoadSettlementListPort,
        SaveSettlementPort,
        DeleteSettlementPort,
        SettlementReceivingAccountUsagePort {
    private final SettlementJpaRepository settlementJpaRepository;

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
        return settlementJpaRepository.findByIdForUpdate(settlementId).map(SettlementMapper::toDomain);
    }

    @Override
    public java.util.List<SettlementListReadModel> findAll(
            Long userId,
            SettlementListTab tab,
            SettlementListStatusFilter status
    ) {
        return settlementJpaRepository.findAllForUser(userId, tab.name(), status.name())
                .stream()
                .map(projection -> toReadModel(projection, tab))
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
            SettlementListTab tab
    ) {
        SettlementStatus status = SettlementStatus.valueOf(projection.getStatus());
        Integer completedParticipantCount = status == SettlementStatus.COMPLETED
                ? Math.toIntExact(projection.getParticipantCount())
                : null;
        return new SettlementListReadModel(
                projection.getSettlementId(),
                status,
                projection.getRequestedAt(),
                tab == SettlementListTab.RECEIVE ? projection.getTotalAmount() : null,
                tab == SettlementListTab.RECEIVE
                        ? Math.toIntExact(projection.getParticipantCount())
                        : null,
                tab == SettlementListTab.RECEIVE ? completedParticipantCount : null,
                tab == SettlementListTab.PAY ? projection.getAmountToPay() : null,
                tab == SettlementListTab.PAY ? projection.getRequesterName() : null
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
