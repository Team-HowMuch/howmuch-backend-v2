package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveSettlementLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementLinkParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantSelectionResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementLinkEntryService implements
        ResolveSettlementLinkUseCase,
        SelectSettlementLinkParticipantUseCase {
    private final SettlementLinkTokenPort linkTokenPort;
    private final LoadSettlementLinkPort loadSettlementLinkPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final CurrentActorPort currentActorPort;
    private final IssueSettlementParticipantContextPort issueParticipantContextPort;

    @Override
    public SettlementLinkEntryResult resolve(String rawToken) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LinkAccess access = loadAccess(rawToken, now);
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(access.settlement().id());
        SettlementParticipant matchedParticipant = currentActorPort.findCurrentActor()
                .map(CurrentActor::userId)
                .flatMap(userId -> participants.stream()
                        .filter(participant -> participant.isLinkedTo(userId))
                        .findFirst())
                .orElse(null);

        if (matchedParticipant != null) {
            SettlementParticipantContext context = issueContext(access, matchedParticipant, now);
            return new SettlementLinkEntryResult(
                    access.settlement().id(),
                    access.settlement().title(),
                    access.phase(),
                    SettlementLinkEntryMode.APP_DIRECT,
                    toResult(matchedParticipant),
                    List.of(),
                    context.token(),
                    context.expiresAt()
            );
        }

        return new SettlementLinkEntryResult(
                access.settlement().id(),
                access.settlement().title(),
                access.phase(),
                SettlementLinkEntryMode.BROWSER_SELECTION,
                null,
                participants.stream().map(this::toResult).toList(),
                null,
                null
        );
    }

    @Override
    public SettlementLinkParticipantSelectionResult select(
            String rawToken,
            UUID participantId
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LinkAccess access = loadAccess(rawToken, now);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(access.settlement().id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        SettlementParticipantContext context = issueContext(access, participant, now);
        return new SettlementLinkParticipantSelectionResult(
                access.settlement().id(),
                toResult(participant),
                access.phase(),
                context.token(),
                context.expiresAt()
        );
    }

    private LinkAccess loadAccess(String rawToken, LocalDateTime now) {
        String tokenHash = linkTokenPort.hash(rawToken);
        SettlementLink link = loadSettlementLinkPort.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        if (!link.isUsableAt(now)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
        }
        Settlement settlement = loadSettlementPort.findById(link.settlementId())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        return new LinkAccess(link, settlement, resolvePhase(settlement));
    }

    private SettlementRequestPhase resolvePhase(Settlement settlement) {
        if (settlement.allocationType() == SettlementAllocationType.DUTCH_PAY
                && settlement.status() == SettlementStatus.AWAITING_DEPOSIT) {
            return SettlementRequestPhase.TRANSFER_READY;
        }
        if (settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION) {
            if (settlement.status() == SettlementStatus.REQUESTED) {
                return SettlementRequestPhase.ITEM_SELECTION;
            }
            if (settlement.status() == SettlementStatus.AWAITING_DEPOSIT) {
                return SettlementRequestPhase.TRANSFER_READY;
            }
        }
        throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
    }

    private SettlementParticipantContext issueContext(
            LinkAccess access,
            SettlementParticipant participant,
            LocalDateTime now
    ) {
        return issueParticipantContextPort.issue(
                access.link().tokenHash(),
                access.settlement().id(),
                participant.id(),
                now
        );
    }

    private SettlementLinkParticipantResult toResult(SettlementParticipant participant) {
        return new SettlementLinkParticipantResult(
                participant.id(),
                participant.displayName()
        );
    }

    private record LinkAccess(
            SettlementLink link,
            Settlement settlement,
            SettlementRequestPhase phase
    ) {
    }
}
