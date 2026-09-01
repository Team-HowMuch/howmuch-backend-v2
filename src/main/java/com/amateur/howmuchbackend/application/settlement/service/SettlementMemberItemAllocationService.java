package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveMyItemAllocationUseCase;
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
 * 참여자 본인이 앱에서 자기 몫을 고른다. 링크 토큰 없이 로그인만으로 쓴다.
 *
 * <p>생성자 전용인 {@code SettlementCreatorItemAllocationService}와 대칭이며, 다른 점은
 * 참여자 확인 방식과 DRAFT를 허용하지 않는 것뿐이다.
 */
@Service
@RequiredArgsConstructor
public class SettlementMemberItemAllocationService
        implements ResolveMyItemAllocationUseCase {
    private static final SettlementItemAllocationEntry ENTRY =
            SettlementItemAllocationEntry.MEMBER;

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementItemAllocationWriter writer;
    private final SettlementItemAllocationReader reader;

    @Override
    public SettlementItemAllocationResult getMyAllocation(UUID settlementId) {
        UUID participantId = resolveMyParticipantId(settlementId);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult saveMySelection(
            UUID settlementId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    ) {
        UUID participantId = resolveMyParticipantId(settlementId);
        writer.save(settlementId, participantId, itemId, command, now(), ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult removeMySelection(UUID settlementId, UUID itemId) {
        UUID participantId = resolveMyParticipantId(settlementId);
        writer.remove(settlementId, participantId, itemId, ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    @Override
    public SettlementItemAllocationResult submitMySelection(UUID settlementId) {
        UUID participantId = resolveMyParticipantId(settlementId);
        writer.submit(settlementId, participantId, now(), ENTRY);
        return reader.read(settlementId, participantId, ENTRY);
    }

    private UUID resolveMyParticipantId(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        return loadSettlementParticipantPort.findAllBySettlementId(settlement.id()).stream()
                .filter(participant -> participant.isLinkedTo(actor.userId()))
                .findFirst()
                .map(SettlementParticipant::id)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ACCESS_DENIED
                ));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
