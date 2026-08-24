package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementRequestPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementRequestService implements RequestSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final SettlementDutchPayAllocationStorePort allocationStorePort;
    private final LoadAccountPort loadAccountPort;
    private final LoadUserPort loadUserPort;
    private final SettlementRequestPolicy requestPolicy;
    private final SettlementLinkTokenPort linkTokenPort;
    private final SaveSettlementRequestPort saveSettlementRequestPort;

    @Override
    @Transactional
    public SettlementRequestResult request(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement requested = settlement.request(now);
        Account account = loadReceivingAccount(settlement, actor.userId());
        User creator = loadUserPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        List<SettlementShare> shares = requestPolicy.prepareShares(
                settlement,
                loadSettlementParticipantPort.findAllBySettlementId(settlement.id()),
                loadSettlementExpensePort.findAllBySettlementId(settlement.id()),
                settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                        ? loadSettlementItemPort.findAllBySettlementId(settlement.id())
                        : List.of(),
                settlement.allocationType() == SettlementAllocationType.DUTCH_PAY
                        ? allocationStorePort.findAllBySettlementId(settlement.id())
                        : List.of(),
                now
        );

        SettlementLinkTokenPort.IssuedSettlementLinkToken issuedToken = linkTokenPort.issue(now);
        SettlementLink link = SettlementLink.active(
                settlement.id(),
                issuedToken.tokenHash(),
                issuedToken.expiresAt(),
                now
        );
        SettlementReceivingAccountSnapshot snapshot = SettlementReceivingAccountSnapshot.create(
                settlement.id(),
                account.id(),
                account.organization(),
                account.externalAccountKey(),
                creator.name(),
                now
        );
        Settlement finalized = settlement.allocationType() == SettlementAllocationType.DUTCH_PAY
                ? requested.awaitDeposit(now)
                : requested;

        Settlement saved = saveSettlementPort.update(finalized);
        saveSettlementRequestPort.save(snapshot, shares, link);
        return new SettlementRequestResult(
                saved.id(),
                issuedToken.url(),
                issuedToken.expiresAt(),
                settlement.allocationType() == SettlementAllocationType.DUTCH_PAY
                        ? SettlementRequestPhase.TRANSFER_READY
                        : SettlementRequestPhase.ITEM_SELECTION,
                saved.status()
        );
    }

    private Account loadReceivingAccount(Settlement settlement, Long creatorId) {
        if (settlement.receivingAccountId() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REQUEST_RECEIVING_ACCOUNT_REQUIRED
            );
        }
        Account account = loadAccountPort.findActiveByIdAndUserId(
                        settlement.receivingAccountId(), creatorId
                )
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        if (!account.isActiveWonDemandDeposit()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED
            );
        }
        return account;
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }
}
