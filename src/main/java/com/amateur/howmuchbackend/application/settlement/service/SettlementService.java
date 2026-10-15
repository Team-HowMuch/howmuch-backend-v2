package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DeleteSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ResumeSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SuspendSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDeletionPolicy;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
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
        DeleteSettlementUseCase,
        UpdateSettlementDraftUseCase,
        SuspendSettlementUseCase,
        ResumeSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final DeleteSettlementPort deleteSettlementPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final SaveSettlementParticipantPort saveSettlementParticipantPort;
    private final SettlementItemAllocationStorePort settlementItemAllocationStorePort;
    private final SettlementDeletionPolicy settlementDeletionPolicy;
    private final LoadUserPort loadUserPort;
    private final SettlementAllocationDraftInvalidator allocationDraftInvalidator;
    private final SettlementDutchPayResumer dutchPayResumer;
    private final SettlementSuspensionService suspensionService;
    private final SettlementRevisionGuard revisionGuard;
    private final DeleteSettlementSharePort deleteSettlementSharePort;

    @Override
    @Transactional
    public SettlementResult create(CreateSettlementCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User creator = loadUserPort.findByIdForUpdate(actor.userId())
                .filter(User::active)
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
    @Transactional
    public void delete(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        settlementDeletionPolicy.validate(
                settlement,
                loadSettlementSharePort.existsBySettlementId(settlementId)
        );
        deleteSettlementPort.delete(settlementId, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public SettlementResult update(UUID settlementId, UpdateSettlementDraftCommand command) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement updateBase = command.totalAmount() != null
                && command.totalAmount() != settlement.totalAmount()
                ? allocationDraftInvalidator.invalidate(settlement, now)
                : settlement;
        Settlement updated = updateBase.updateDraft(
                command.title(),
                command.totalAmount(),
                now
        );
        return SettlementResult.from(saveSettlementPort.update(updated));
    }

    @Override
    public SettlementResult suspend(UUID settlementId) {
        return suspensionService.suspend(settlementId);
    }

    @Override
    @Transactional
    public SettlementResult resume(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement resumed = settlement.resume(now);
        if (settlement.allocationType() == SettlementAllocationType.DUTCH_PAY) {
            resumed = dutchPayResumer.resume(resumed, now);
        } else if (settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION) {
            revisionGuard.ensureUnpaid(settlementId);
            deleteSettlementSharePort.deleteAllBySettlementId(settlementId);
        }
        settlementItemAllocationStorePort.deleteAllSubmissions(settlementId);
        return SettlementResult.from(saveSettlementPort.update(resumed));
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        return verifyOwnership(settlement, userId);
    }

    private Settlement verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }
}
