package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.notification.service.NotificationMessages;
import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementRequestPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementCategoryClassificationPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 정산 요청의 실제 기록 처리. 즉시 요청({@link SettlementRequestService})과 반복 발행
 * ({@link RecurringSettlementWorker})이 공유한다. 업무 트랜잭션이 이미 열려 있어야 한다(MANDATORY).
 *
 * <p>카테고리는 반복 발행처럼 명시적 선택이 있으면 {@code source=RECURRING}으로 그대로 기록하고 LLM
 * 분류를 호출하지 않는다. 그 밖에는 기존 즉시 요청과 동일하게 분류 예약 또는 미분류 저장을 따른다.
 */
@Service
@RequiredArgsConstructor
public class SettlementRequestWriter {
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
    private final NotificationRecorder notificationRecorder;
    private final SettlementCategoryJobStorePort categoryJobStorePort;
    private final SettlementCategoryClassificationPolicy categoryPolicy;
    private final NettingEstimateInvalidationService estimateInvalidation;

    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementRequestResult request(UUID settlementId, Long creatorId, LocalDateTime now) {
        return request(settlementId, creatorId, now, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementRequestResult request(UUID settlementId, Long creatorId, LocalDateTime now,
            String recurringCategoryCode) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, creatorId);
        Settlement requested = settlement.request(now);
        List<SettlementParticipant> participants =
                loadSettlementParticipantPort.findAllBySettlementId(settlement.id());
        validateActiveGroupMembers(participants);
        Account account = loadReceivingAccount(settlement, creatorId);
        User creator = loadUserPort.findById(creatorId)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        List<SettlementExpense> expenses = loadSettlementExpensePort.findAllBySettlementId(settlement.id());
        List<SettlementShare> shares = requestPolicy.prepareShares(
                settlement,
                participants,
                expenses,
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
        estimateInvalidation.settlementChanged(saved.id());
        if (recurringCategoryCode != null) {
            var source = SettlementCategory.UNCATEGORIZED.equals(recurringCategoryCode)
                    ? SettlementCategorySource.UNCATEGORIZED : SettlementCategorySource.RECURRING;
            categoryJobStorePort.saveRecurring(saved.id(), recurringCategoryCode, source, now);
        } else if (categoryPolicy.classifiable(expenses)) {
            categoryJobStorePort.schedule(saved.id(), now);
        } else {
            categoryJobStorePort.saveUncategorized(saved.id(), now);
        }
        createParticipationNotifications(
                saved.id(),
                saved.creatorId(),
                participants,
                shares,
                now, creator.name(), saved.allocationType()
        );
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

    private void createParticipationNotifications(
            UUID settlementId,
            Long creatorId,
            List<SettlementParticipant> participants,
            List<SettlementShare> shares,
            LocalDateTime now, String requesterName, SettlementAllocationType allocationType
    ) {
        Set<UUID> payerIds = shares.stream()
                .map(SettlementShare::payerParticipantId)
                .collect(Collectors.toSet());
        List<Long> targetUserIds = participants.stream()
                .filter(participant -> allocationType != SettlementAllocationType.DUTCH_PAY
                        || payerIds.contains(participant.id()))
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .filter(userId -> !userId.equals(creatorId))
                .distinct()
                .toList();
        if (targetUserIds.isEmpty()) {
            return;
        }
        loadUserPort.findAllByIds(targetUserIds).stream()
                .filter(User::active)
                .forEach(user -> notificationRecorder.record(NotificationMessages.request(
                        settlementId, user.id(), allocationType, requesterName, now)));

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
