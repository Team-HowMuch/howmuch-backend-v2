package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetMySettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveMySettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveMySettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitMySettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 생성자가 알림을 보내기 전에 자기 품목 몫을 고르는 경로.
 *
 * <p>링크 경로({@link SettlementItemAllocationService})와 같은 Reader/Writer를 쓰고,
 * 진입 경로만 {@code CREATOR}로 넘겨 DRAFT를 허용한다.
 */
@Service
@RequiredArgsConstructor
public class SettlementCreatorItemAllocationService implements
        GetMySettlementItemAllocationUseCase,
        SaveMySettlementItemSelectionUseCase,
        RemoveMySettlementItemSelectionUseCase,
        SubmitMySettlementItemAllocationUseCase {
    private static final SettlementItemAllocationEntry ENTRY = SettlementItemAllocationEntry.CREATOR;

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementItemAllocationWriter writer;
    private final SettlementItemAllocationReader reader;

    @Override
    public SettlementItemAllocationResult getMyItems(UUID settlementId) {
        UUID participantId = resolveCreatorParticipantId(settlementId);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult saveMyItem(
            UUID settlementId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    ) {
        UUID participantId = resolveCreatorParticipantId(settlementId);
        writer.save(settlementId, participantId, itemId, command, now(), ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult removeMyItem(UUID settlementId, UUID itemId) {
        UUID participantId = resolveCreatorParticipantId(settlementId);
        writer.remove(settlementId, participantId, itemId, ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult submitMyItems(UUID settlementId) {
        UUID participantId = resolveCreatorParticipantId(settlementId);
        writer.submit(settlementId, participantId, now(), ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    private UUID resolveCreatorParticipantId(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(actor.userId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return loadSettlementParticipantPort.findAllBySettlementId(settlement.id()).stream()
                .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                .findFirst()
                .map(SettlementParticipant::id)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND
                ));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
