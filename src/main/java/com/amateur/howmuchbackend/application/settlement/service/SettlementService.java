package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementService implements
        CreateSettlementUseCase,
        GetSettlementUseCase,
        UpdateSettlementDraftUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SaveSettlementParticipantPort saveSettlementParticipantPort;
    private final LoadUserPort loadUserPort;

    @Override
    @Transactional
    public SettlementResult create(CreateSettlementCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User creator = loadUserPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement settlement = Settlement.draft(
                actor.userId(),
                command.title(),
                command.totalAmount(),
                now
        );
        Settlement saved = saveSettlementPort.save(settlement);
        saveSettlementParticipantPort.save(SettlementParticipant.registeredUser(
                saved.id(),
                creator.id(),
                creator.name(),
                0,
                now
        ));
        return SettlementResult.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementResult get(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        return SettlementResult.from(loadOwnedSettlement(settlementId, actor.userId()));
    }

    @Override
    @Transactional
    public SettlementResult update(UUID settlementId, UpdateSettlementDraftCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        Settlement updated = settlement.updateDraft(
                command.title(),
                command.totalAmount(),
                LocalDateTime.now(ZoneOffset.UTC)
        );
        return SettlementResult.from(saveSettlementPort.update(updated));
    }

    private Settlement loadOwnedSettlement(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }
}
