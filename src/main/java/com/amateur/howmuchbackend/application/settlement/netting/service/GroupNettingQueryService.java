package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
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

    public List<GroupNettingResult> list(long groupId) {
        long user = actor.getCurrentActor().userId();
        groups.requireActiveMember(groupId, user);
        boolean deleted =
                groupRecords.findById(groupId).orElseThrow().status()
                        == com.amateur.howmuchbackend.domain.group.GroupStatus.DELETED;
        return nettings.findAll(groupId).stream()
                .map(id -> nettings.find(groupId, id).orElseThrow())
                .filter(n -> !deleted || n.partyIds().contains(user))
                .map(this::result)
                .toList();
    }

    public GroupNettingResult current(long groupId) {
        return list(groupId).stream()
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
    }

    public GroupNettingResult get(long groupId, UUID id) {
        return result(access.requireAccess(groupId, id, actor.getCurrentActor().userId()));
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

    private GroupNettingResult result(LoadGroupNettingPort.Netting n) {
        var partyIds =
                n.transfers().stream()
                        .flatMap(t -> java.util.stream.Stream.of(t.payerId(), t.receiverId()))
                        .distinct()
                        .toList();
        var parties =
                users.findAllByIds(partyIds).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        User::id, java.util.function.Function.identity()));
        return new GroupNettingResult(
                n.id(),
                n.groupId(),
                n.version(),
                n.status(),
                n.openReviews(),
                n.createdAt(),
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
                                                s.replacedAmount()))
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
                                                parties.get(t.payerId()).name(),
                                                parties.get(t.payerId()).publicId(),
                                                parties.get(t.receiverId()).name(),
                                                parties.get(t.receiverId()).publicId()))
                        .toList());
    }
}
