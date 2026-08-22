package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementParticipantMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementParticipantPersistenceAdapter implements SaveSettlementParticipantPort {
    private final SettlementParticipantJpaRepository settlementParticipantJpaRepository;

    @Override
    public SettlementParticipant save(SettlementParticipant participant) {
        return SettlementParticipantMapper.toDomain(
                settlementParticipantJpaRepository.saveAndFlush(
                        SettlementParticipantMapper.toEntity(participant)
                )
        );
    }
}
