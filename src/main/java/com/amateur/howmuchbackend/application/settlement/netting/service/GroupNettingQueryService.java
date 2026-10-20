package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.settlement.service.MySettlementCategoryReader;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import com.amateur.howmuchbackend.domain.user.User;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class GroupNettingQueryService
        implements GetGroupNettingsUseCase, GetNettedTransferInstructionUseCase {
    private final CurrentActorPort actor;
    private final GroupNettingAccessService access;
    private final GroupAccessService groups;
    private final com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort groupRecords;
    private final LoadGroupNettingPort nettings;
    private final LoadUserPort users;
    private final SettlementTransferLinkPort links;
    private final NettingTransferAvailabilityPort availability;
    private final MySettlementCategoryReader categories;

    public List<GroupNettingResult> list(long groupId) {
        long user = actor.getCurrentActor().userId();
        groups.requireActiveMember(groupId, user);
        boolean deleted =
                groupRecords.findById(groupId).orElseThrow().status()
                        == com.amateur.howmuchbackend.domain.group.GroupStatus.DELETED;
        return nettings.findAll(groupId).stream()
                .map(id -> nettings.find(groupId, id).orElseThrow())
                .filter(n -> !deleted || n.partyIds().contains(user))
                .map(n -> result(n, user))
                .toList();
    }

    public GroupNettingResult current(long groupId) {
        return list(groupId).stream()
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
    }

    public GroupNettingResult get(long groupId, UUID id) {
        long user = actor.getCurrentActor().userId();
        return result(access.requireAccess(groupId, id, user), user);
    }

    public GroupNettingResult getById(UUID id) {
        var n =
                nettings.findById(id)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        return get(n.groupId(), id);
    }

    public GroupNettingResult getBySource(UUID settlementId) {
        var n =
                nettings.findBySource(settlementId)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        return get(n.groupId(), n.id());
    }

    public NettedTransferInstructionResult getInstruction(long groupId, UUID id, UUID transferId) {
        long user = actor.getCurrentActor().userId();
        var n = access.requireAccess(groupId, id, user);
        if (!"ACTIVE".equals(n.status()))
            throw ApplicationException.of(NettingStatusCode.NETTING_TRANSFER_REPLACED);
        var transfer =
                n.transfers().stream()
                        .filter(t -> t.id().equals(transferId))
                        .findFirst()
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        if (transfer.payerId() != user)
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        if (!"ACTIVE".equals(n.status()) || transfer.remainingAmount() <= 0)
            throw ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND);
        if (availability.isGroupReplacementPending(groupId))
            throw ApplicationException.of(NettingStatusCode.NETTING_PROCESSING);
        if (n.openReviews() > 0)
            throw ApplicationException.of(NettingStatusCode.NETTING_REVIEW_REQUIRED);
        var account = nettings.receivingAccount(id, transfer.receiverId());
        var receiver = users.findById(transfer.receiverId()).orElseThrow();
        var link =
                links.create(
                                account.organization(),
                                account.accountNumber(),
                                transfer.remainingAmount(),
                                receiver.name())
                        .orElse(null);
        return new NettedTransferInstructionResult(
                id,
                transferId,
                account.organization(),
                account.accountNumber(),
                receiver.name(),
                transfer.remainingAmount(),
                transfer.reference(),
                link == null ? null : link.provider(),
                link == null ? null : link.url());
    }

    private GroupNettingResult result(LoadGroupNettingPort.Netting n, long viewerId) {
        var sourceCategories = categories.read(
                viewerId, n.sources().stream().map(LoadGroupNettingPort.SourceAmount::settlementId).toList());
        var displayUserIds =
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(n.creatorId()),
                                n.transfers().stream()
                                        .flatMap(t -> java.util.stream.Stream.of(t.payerId(), t.receiverId())))
                        .distinct()
                        .toList();
        var displayUsers =
                users.findAllByIds(displayUserIds).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        User::id, java.util.function.Function.identity()));
        return new GroupNettingResult(
                n.id(),
                n.groupId(),
                n.version(),
                n.status(),
                n.openReviews(),
                n.creatorId(),
                displayUsers.get(n.creatorId()).name(),
                n.createdAt(),
                n.completedAt(),
                n.supersededAt(),
                n.sources().stream()
                        .map(
                                s ->
                                        new GroupNettingResult.Source(
                                                s.settlementId(),
                                                s.title(),
                                                s.status(),
                                                s.requesterName(),
                                                s.requestedAt(),
                                                s.allocationType(),
                                                s.originalAmount(),
                                                s.actualDepositAmount(),
                                                s.replacedAmount(),
                                                s.storeName(),
                                                sourceCategories.get(s.settlementId()),
                                                s.participantCount(),
                                                s.payableParticipantCount(),
                                                s.completedParticipantCount()))
                        .toList(),
                n.transfers().stream()
                        .map(
                                t ->
                                        new GroupNettingResult.Transfer(
                                                t.id(),
                                                t.payerId(),
                                                t.receiverId(),
                                                t.amount(),
                                                t.confirmedAmount(),
                                                t.reference(),
                                                t.organization(),
                                                t.maskedNumber(),
                                                displayUsers.get(t.payerId()).name(),
                                                displayUsers.get(t.payerId()).publicId(),
                                                displayUsers.get(t.receiverId()).name(),
                                                displayUsers.get(t.receiverId()).publicId(),
                                                t.confirmedAmount() == 0
                                                        ? GroupNettingResult.TransferStatus.UNPAID
                                                        : t.remainingAmount() > 0
                                                                ? GroupNettingResult.TransferStatus.PARTIAL
                                                                : GroupNettingResult.TransferStatus.PAID,
                                                t.confirmedAt(),
                                                n.sources().stream()
                                                        .filter(s -> s.debtPartyIds().contains(t.payerId())
                                                                || s.debtPartyIds().contains(t.receiverId()))
                                                        .map(LoadGroupNettingPort.SourceAmount::settlementId)
                                                        .distinct()
                                                        .toList()))
                        .toList());
    }
}
