package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementRequestPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final LockGroupMemberRosterPort lockGroupMemberRosterPort;
    private final SettlementNotificationOutboxPort notificationOutboxPort;

    @Override
    @Transactional
    public SettlementRequestResult request(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Settlement requested = settlement.request(now);
        List<SettlementParticipant> participants =
                loadSettlementParticipantPort.findAllBySettlementId(settlement.id());
        validateActiveGroupMembers(participants);
        Account account = loadReceivingAccount(settlement, actor.userId());
        User creator = loadUserPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        List<SettlementShare> shares = requestPolicy.prepareShares(
                settlement,
                participants,
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
        saveSettlementRequestPort.save(snapshot, shares, link, issuedToken.rawToken());
        notificationOutboxPort.saveAll(createParticipationNotificationEvents(
                saved.id(),
                saved.creatorId(),
                participants,
                now
        ));
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

    private List<SettlementNotificationOutbox> createParticipationNotificationEvents(
            UUID settlementId,
            Long creatorId,
            List<SettlementParticipant> participants,
            LocalDateTime now
    ) {
        List<Long> targetUserIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .filter(userId -> !userId.equals(creatorId))
                .distinct()
                .toList();
        if (targetUserIds.isEmpty()) {
            return List.of();
        }
        return loadUserPort.findAllByIds(targetUserIds).stream()
                .filter(User::active)
                .filter(user -> user.fcmToken() != null && !user.fcmToken().isBlank())
                .map(user -> SettlementNotificationOutbox.pending(
                        settlementId,
                        user.id(),
                        now
                ))
                .toList();
    }

    private void validateActiveGroupMembers(List<SettlementParticipant> participants) {
        Map<Long, List<Long>> userIdsByGroupId = participants.stream()
                .filter(participant -> participant.source() == SettlementParticipantSource.GROUP_MEMBER)
                .collect(Collectors.groupingBy(
                        SettlementParticipant::sourceGroupId,
                        TreeMap::new,
                        Collectors.mapping(SettlementParticipant::linkedUserId, Collectors.toList())
                ));

        userIdsByGroupId.forEach((groupId, userIds) -> {
            var roster = lockGroupMemberRosterPort.lockAndLoad(groupId)
                    .orElseThrow(() -> ApplicationException.of(
                            SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                    ));
            if (userIds.stream().anyMatch(userId -> !roster.contains(userId))) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                );
            }
        });
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
