package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementRequestMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
import java.util.UUID;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementSharePersistenceAdapter implements SaveSettlementSharePort, DeleteSettlementSharePort {
    private final SettlementShareJpaRepository shareRepository;

    @Override
    public void deleteAllBySettlementId(UUID settlementId) {
        shareRepository.deleteAllBySettlementId(settlementId);
        shareRepository.flush();
    }

    @Override
    public void saveAll(List<SettlementShare> shares) {
        if (shares == null || shares.isEmpty()) {
            throw new IllegalArgumentException("저장할 정산 Share가 필요합니다.");
        }
        shareRepository.saveAllAndFlush(shares.stream()
                .map(SettlementRequestMapper::toEntity)
                .toList());
    }
}
