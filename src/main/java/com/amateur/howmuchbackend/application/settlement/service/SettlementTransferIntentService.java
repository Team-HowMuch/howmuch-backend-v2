package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.*;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferIntentResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementTransferReconciliationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementTransferIntentService implements RecordMySettlementTransferIntentUseCase,
        RecordSettlementTransferIntentUseCase {
    private final CurrentActorPort actor;
    private final LoadSettlementPort settlements;
    private final LoadSettlementParticipantPort participants;
    private final LoadSettlementSharePort shares;
    private final LoadSettlementDepositSummaryPort deposits;
    private final SettlementLinkTokenPort linkTokens;
    private final LoadSettlementLinkPort links;
    private final LoadSettlementParticipantContextPort contexts;
    private final SettlementDepositReconciliationJobStorePort jobs;
    private final SettlementTransferReconciliationPolicy policy;
    private final Clock clock;

    @Override
    @Transactional
    public SettlementTransferIntentResult recordMyIntent(UUID settlementId) {
        Long userId = actor.getCurrentActor().userId();
        Settlement settlement = lockSettlement(settlementId);
        var participant = participants.findAllBySettlementId(settlementId).stream()
                .filter(candidate -> candidate.isLinkedTo(userId)).findFirst()
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        return schedule(settlement, participant, now());
    }

    @Override
    @Transactional
    public SettlementTransferIntentResult recordIntent(String rawLinkToken, UUID participantContext) {
        String hash = linkTokens.hash(rawLinkToken);
        var link = usableLink(hash, now());
        Settlement settlement = lockSettlement(link.settlementId());
        LocalDateTime now = now();
        // 정산 잠금을 기다리는 동안의 링크 만료·취소도 다시 검사한다.
        usableLink(hash, now);
        var context = contexts.findByToken(participantContext)
                .filter(candidate -> candidate.isUsableAt(now))
                .filter(candidate -> candidate.linkTokenHash().equals(hash))
                .filter(candidate -> candidate.settlementId().equals(settlement.id()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND));
        var participant = participants.findBySettlementIdAndId(settlement.id(), context.participantId())
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND));
        return schedule(settlement, participant, now);
    }

    private SettlementTransferIntentResult schedule(Settlement settlement,
            SettlementParticipant participant, LocalDateTime now) {
        var share = shares.findBySettlementIdAndPayerParticipantId(settlement.id(), participant.id())
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_TRANSFER_INTENT_NOT_AVAILABLE));
        long confirmed = deposits.findConfirmedShareAmountsBySettlementId(settlement.id()).stream()
                .filter(row -> row.shareId().equals(share.id())).mapToLong(row -> row.amount()).sum();
        policy.validateSignal(settlement, participant, share, confirmed);
        var job = jobs.schedule(settlement.id(), now, now.plus(SettlementTransferReconciliationPolicy.INITIAL_DELAY),
                now.plus(SettlementTransferReconciliationPolicy.WINDOW),
                now.minus(SettlementTransferReconciliationPolicy.WINDOW))
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_TRANSFER_INTENT_COOLDOWN));
        return SettlementTransferIntentResult.from(job);
    }

    private Settlement lockSettlement(UUID id) {
        return settlements.findByIdForUpdate(id)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
    }

    private SettlementLink usableLink(String hash, LocalDateTime now) {
        return links.findByTokenHash(hash).filter(link -> link.isUsableAt(now))
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND));
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
