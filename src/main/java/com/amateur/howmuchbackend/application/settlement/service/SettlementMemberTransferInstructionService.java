package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveMyTransferInstructionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 참여자 본인이 앱에서 자기 송금 안내를 본다. 링크 토큰 없이 로그인만으로 쓴다.
 *
 * <p>링크용인 {@code SettlementTransferInstructionService}와 대칭이며, 다른 점은
 * 참여자를 찾는 방식뿐이다. 조회·조립은 같은 Reader를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class SettlementMemberTransferInstructionService
        implements ResolveMyTransferInstructionUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementTransferInstructionReader reader;

    @Override
    @Transactional(readOnly = true)
    public SettlementTransferInstructionResult getMyTransferInstruction(UUID settlementId) {
        return reader.read(settlementId, resolveMyParticipantId(settlementId));
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
}
