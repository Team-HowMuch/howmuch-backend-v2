package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementParticipantModificationPolicy;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
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
public class SettlementParticipantService implements
        AddSettlementParticipantUseCase,
        RemoveSettlementParticipantUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SaveSettlementParticipantPort saveSettlementParticipantPort;
    private final DeleteSettlementParticipantPort deleteSettlementParticipantPort;
    private final LoadUserPort loadUserPort;
    private final PublicIdPolicy publicIdPolicy;
    private final IdentityPolicy identityPolicy;
    private final SettlementParticipantModificationPolicy modificationPolicy;

    @Override
    @Transactional
    public SettlementParticipantResult add(
            UUID settlementId,
            AddSettlementParticipantCommand command
    ) {
        Settlement settlement = loadOwnedEditableSettlement(settlementId);
        ResolvedParticipant resolved = resolveParticipant(command);
        int displayOrder = loadSettlementParticipantPort.nextDisplayOrder(settlement.id());
        SettlementParticipant participant = resolved.create(
                settlement.id(),
                displayOrder,
                LocalDateTime.now(ZoneOffset.UTC)
        );
        modificationPolicy.validateAddition(participantAlreadyExists(settlement.id(), resolved));
        SettlementParticipant saved = saveSettlementParticipantPort.save(participant);
        return SettlementParticipantResult.of(saved, resolved.publicId());
    }

    @Override
    @Transactional
    public void remove(UUID settlementId, UUID participantId) {
        Settlement settlement = loadOwnedEditableSettlement(settlementId);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        modificationPolicy.validateRemoval(settlement, participant);
        deleteSettlementParticipantPort.delete(participant);
    }

    private Settlement loadOwnedEditableSettlement(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        if (!settlement.isCreatedBy(actor.userId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        settlement.ensureParticipantsEditable();
        return settlement;
    }

    private ResolvedParticipant resolveParticipant(AddSettlementParticipantCommand command) {
        if (command == null || command.source() == null) {
            throw invalidAddRequest();
        }
        return switch (command.source()) {
            case REGISTERED_USER -> resolveRegisteredUser(command);
            case CONTACT -> resolveContact(command);
            case GROUP_MEMBER -> throw invalidAddRequest();
        };
    }

    private ResolvedParticipant resolveRegisteredUser(AddSettlementParticipantCommand command) {
        if (command.publicId() == null
                || command.displayName() != null
                || command.phoneNumber() != null) {
            throw invalidAddRequest();
        }
        String publicId = publicIdPolicy.normalize(command.publicId());
        User user = loadUserPort.findByPublicId(publicId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        return new ResolvedParticipant(
                SettlementParticipantSource.REGISTERED_USER,
                user.id(),
                user.identityKey(),
                user.name(),
                user.publicId()
        );
    }

    private ResolvedParticipant resolveContact(AddSettlementParticipantCommand command) {
        if (command.publicId() != null
                || command.displayName() == null
                || command.phoneNumber() == null) {
            throw invalidAddRequest();
        }
        String phoneHash = identityPolicy.phoneHash(command.phoneNumber());
        User linkedUser = loadUserPort.findByIdentityKey(phoneHash)
                .filter(User::active)
                .orElse(null);
        return new ResolvedParticipant(
                SettlementParticipantSource.CONTACT,
                linkedUser == null ? null : linkedUser.id(),
                phoneHash,
                command.displayName(),
                linkedUser == null ? null : linkedUser.publicId()
        );
    }

    private ApplicationException invalidAddRequest() {
        return ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_ADD_REQUEST);
    }

    private boolean participantAlreadyExists(UUID settlementId, ResolvedParticipant participant) {
        boolean linkedUserExists = participant.linkedUserId() != null
                && loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                        settlementId,
                        participant.linkedUserId()
                );
        boolean phoneHashExists = participant.phoneHash() != null
                && loadSettlementParticipantPort.existsBySettlementIdAndPhoneHash(
                        settlementId,
                        participant.phoneHash()
                );
        return linkedUserExists || phoneHashExists;
    }

    private record ResolvedParticipant(
            SettlementParticipantSource source,
            Long linkedUserId,
            String phoneHash,
            String displayName,
            String publicId
    ) {
        private SettlementParticipant create(
                UUID settlementId,
                int displayOrder,
                LocalDateTime now
        ) {
            return switch (source) {
                case REGISTERED_USER -> SettlementParticipant.registeredUser(
                        settlementId,
                        linkedUserId,
                        displayName,
                        displayOrder,
                        now
                );
                case CONTACT -> linkedUserId == null
                        ? SettlementParticipant.unlinkedContact(
                                settlementId,
                                phoneHash,
                                displayName,
                                displayOrder,
                                now
                        )
                        : SettlementParticipant.linkedContact(
                                settlementId,
                                linkedUserId,
                                displayName,
                                displayOrder,
                                now
                        );
                case GROUP_MEMBER -> throw new IllegalStateException("그룹 참여자는 별도 UseCase에서 생성합니다.");
            };
        }

    }
}
