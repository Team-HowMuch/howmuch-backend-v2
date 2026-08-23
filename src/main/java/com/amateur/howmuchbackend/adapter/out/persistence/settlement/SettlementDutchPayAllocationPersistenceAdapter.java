package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementDutchPayAllocationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementDutchPayAllocationJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementDutchPayAllocationPersistenceAdapter
        implements SettlementDutchPayAllocationStorePort {
    private final SettlementDutchPayAllocationJpaRepository repository;

    @Override
    public List<SettlementDutchPayAllocation> findAllBySettlementId(UUID settlementId) {
        return repository.findAllBySettlementId(settlementId)
                .stream()
                .map(SettlementDutchPayAllocationMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<SettlementDutchPayAllocation> findBySettlementIdAndParticipantId(
            UUID settlementId,
            UUID participantId
    ) {
        return repository.findBySettlementIdAndParticipantId(settlementId, participantId)
                .map(SettlementDutchPayAllocationMapper::toDomain);
    }

    @Override
    public List<SettlementDutchPayAllocation> replaceAll(
            UUID settlementId,
            List<SettlementDutchPayAllocation> allocations
    ) {
        repository.deleteAllBySettlementId(settlementId);
        repository.flush();
        return repository.saveAllAndFlush(allocations.stream()
                        .map(SettlementDutchPayAllocationMapper::toEntity)
                        .toList())
                .stream()
                .map(SettlementDutchPayAllocationMapper::toDomain)
                .toList();
    }

    @Override
    public SettlementDutchPayAllocation update(SettlementDutchPayAllocation allocation) {
        var entity = repository.findBySettlementIdAndParticipantId(
                        allocation.settlementId(),
                        allocation.participantId()
                )
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_NOT_FOUND
                ));
        entity.updateFrom(allocation);
        return SettlementDutchPayAllocationMapper.toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public void deleteAllBySettlementId(UUID settlementId) {
        repository.deleteAllBySettlementId(settlementId);
        repository.flush();
    }
}
