package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SettlementService implements CreateSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final SaveSettlementPort saveSettlementPort;

    @Override
    @Transactional
    public SettlementResult create(CreateSettlementCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement settlement = Settlement.draft(
                actor.userId(),
                command.title(),
                command.totalAmount(),
                now
        );
        return SettlementResult.from(saveSettlementPort.save(settlement));
    }
}
