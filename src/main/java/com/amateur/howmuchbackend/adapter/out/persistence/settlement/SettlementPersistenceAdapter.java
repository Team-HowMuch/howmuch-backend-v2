package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementPersistenceAdapter implements LoadSettlementPort, SaveSettlementPort {
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
    public Settlement update(Settlement settlement) {
        var entity = settlementJpaRepository.findById(settlement.id())
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        entity.updateFrom(settlement);
        return SettlementMapper.toDomain(settlementJpaRepository.save(entity));
    }
}
