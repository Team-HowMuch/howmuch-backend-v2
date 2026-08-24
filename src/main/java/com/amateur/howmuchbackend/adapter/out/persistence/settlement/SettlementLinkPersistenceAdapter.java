package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementRequestMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SettlementLinkPersistenceAdapter implements LoadSettlementLinkPort {
    private final SettlementLinkJpaRepository linkRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementLink> findByTokenHash(String tokenHash) {
        return linkRepository.findByTokenHash(tokenHash)
                .map(SettlementRequestMapper::toDomain);
    }
}
