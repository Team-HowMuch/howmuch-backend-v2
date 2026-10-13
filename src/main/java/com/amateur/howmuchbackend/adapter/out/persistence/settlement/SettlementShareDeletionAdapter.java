package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementShareDeletionAdapter implements DeleteSettlementSharePort {
    private final SettlementShareJpaRepository shareRepository;

    @Override
    public void deleteAllBySettlementId(UUID settlementId) {
        shareRepository.deleteAllBySettlementId(settlementId);
        shareRepository.flush();
    }
}
