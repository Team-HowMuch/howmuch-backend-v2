package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementTransferInstructionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementTransferInstructionService
        implements GetSettlementTransferInstructionUseCase {
    private final SettlementLinkTokenPort linkTokenPort;
    private final LoadSettlementLinkPort loadSettlementLinkPort;
    private final LoadSettlementParticipantContextPort loadParticipantContextPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;

    @Override
    @Transactional(readOnly = true)
    public SettlementTransferInstructionResult get(
            String rawLinkToken,
            UUID participantContextToken
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String linkTokenHash = linkTokenPort.hash(rawLinkToken);
        SettlementLink link = loadSettlementLinkPort.findByTokenHash(linkTokenHash)
                .filter(candidate -> candidate.isUsableAt(now))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        SettlementParticipantContext context = loadParticipantContextPort
                .findByToken(participantContextToken)
                .filter(candidate -> candidate.isUsableAt(now))
                .filter(candidate -> candidate.linkTokenHash().equals(linkTokenHash))
                .filter(candidate -> candidate.settlementId().equals(link.settlementId()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                ));
        Settlement settlement = loadSettlementPort.findById(context.settlementId())
                .filter(candidate -> candidate.status() == SettlementStatus.AWAITING_DEPOSIT)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        SettlementShare share = loadSettlementSharePort
                .findBySettlementIdAndPayerParticipantId(
                        settlement.id(),
                        context.participantId()
                )
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        SettlementReceivingAccountSnapshot snapshot = loadSnapshotPort
                .findBySettlementId(settlement.id())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        return new SettlementTransferInstructionResult(
                settlement.id(),
                context.participantId(),
                share.id(),
                share.amount(),
                snapshot.organization(),
                snapshot.accountNumber()
        );
    }
}
