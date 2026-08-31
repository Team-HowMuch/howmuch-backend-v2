package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementItemAllocationService implements
        GetSettlementItemAllocationUseCase,
        SaveSettlementItemSelectionUseCase,
        RemoveSettlementItemSelectionUseCase,
        SubmitSettlementItemAllocationUseCase {
    private final SettlementLinkTokenPort linkTokenPort;
    private final LoadSettlementLinkPort loadSettlementLinkPort;
    private final LoadSettlementParticipantContextPort loadParticipantContextPort;
    private final SettlementItemAllocationWriter writer;
    private final SettlementItemAllocationReader reader;

    @Override
    public SettlementItemAllocationResult get(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId
    ) {
        AccessContext access = resolveAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now()
        );
        return reader.read(access.settlementId(), participantId);
    }

    @Override
    public SettlementItemAllocationResult save(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    ) {
        LocalDateTime now = now();
        AccessContext access = resolveAccess(rawLinkToken, participantContextToken, participantId, now);
        writer.save(access.settlementId(), participantId, itemId, command, now);
        return reader.read(access.settlementId(), participantId);
    }

    @Override
    public SettlementItemAllocationResult remove(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId
    ) {
        AccessContext access = resolveAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now()
        );
        writer.remove(access.settlementId(), participantId, itemId);
        return reader.read(access.settlementId(), participantId);
    }

    @Override
    public SettlementItemAllocationResult submit(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId
    ) {
        LocalDateTime now = now();
        AccessContext access = resolveAccess(rawLinkToken, participantContextToken, participantId, now);
        writer.submit(access.settlementId(), participantId, now);
        return reader.read(access.settlementId(), participantId);
    }

    private AccessContext resolveAccess(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            LocalDateTime now
    ) {
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
                .filter(candidate -> candidate.participantId().equals(participantId))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                ));
        return new AccessContext(context.settlementId());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record AccessContext(UUID settlementId) {
    }
}
