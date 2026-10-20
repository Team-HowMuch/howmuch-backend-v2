package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.netting.calculator.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCompressionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class GroupNettingInputReader {
    private final GroupAccessService groups;
    private final LoadGroupMemberPort members;
    private final LoadUserPort users;
    private final LoadAccountPort accounts;
    private final LoadGroupNettingDebtPort debts;
    private final NettingCalculationEngine engine;
    private final LoadGroupNettingPort nettings;
    private final NettingCompressionPolicy compression;

    public NettingInput read(long groupId) {
        return readChecked(groupId, null, true);
    }

    public NettingInput readForEstimate(long groupId) {
        return readChecked(groupId, null, false);
    }

    public void requireCompressibleScope(long groupId) {
        compression.requireCompressible(
                Math.addExact(
                        eligibleSources(groupId).size(),
                        nettings.findActive(groupId).isPresent() ? 1 : 0));
    }

    public NettingInput recalculate(long groupId, NettingCalculatorSelection selection) {
        if (selection == null)
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        return readChecked(groupId, selection, true);
    }

    private NettingInput readChecked(
            long groupId, NettingCalculatorSelection selection, boolean requireMultiple) {
        try {
            return readValidated(groupId, selection, requireMultiple);
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_SOURCE);
        }
    }

    private List<NettingInput.Source> eligibleSources(long groupId) {
        return debts.findAwaitingSources(groupId).stream()
                .filter(source -> eligible(source, groupId))
                .filter(source -> source.debts().stream().anyMatch(d -> d.remainingAmount() > 0))
                .toList();
    }

    private NettingInput readValidated(
            long groupId, NettingCalculatorSelection selection, boolean requireMultiple) {
        var group = groups.loadActive(groupId);
        if (debts.hasOpenReviews(groupId))
            throw ApplicationException.of(NettingStatusCode.NETTING_ALREADY_ACTIVE);
        var sources = eligibleSources(groupId);
        var predecessor = nettings.findActive(groupId).orElse(null);
        if (sources.isEmpty() && predecessor == null)
            throw ApplicationException.of(NettingStatusCode.NETTING_NO_ELIGIBLE_DEBTS);
        if (requireMultiple)
            compression.requireCompressible(
                    Math.addExact(sources.size(), predecessor == null ? 0 : 1));
        for (var source : sources) {
            if (source.receivingAccountId() <= 0)
                throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_SOURCE);
            if (!debts.isReceivingAccountConnected(source.receivingAccountId()))
                throw ApplicationException.of(NettingStatusCode.NETTING_RECEIVING_ACCOUNT_REQUIRED);
        }
        var monitoring = new ArrayList<NettingInput.MonitoringAccount>();
        if (predecessor != null)
            for (var monitored : nettings.monitoredAccounts(predecessor.id())) {
                var account =
                        accounts.findActiveByIdAndUserId(monitored.accountId(), monitored.userId())
                                .orElseThrow(
                                        () ->
                                                ApplicationException.of(
                                                        NettingStatusCode
                                                                .NETTING_RECEIVING_ACCOUNT_REQUIRED));
                if (!debts.isReceivingAccountConnected(account.id()))
                    throw ApplicationException.of(
                            NettingStatusCode.NETTING_RECEIVING_ACCOUNT_REQUIRED);
                monitoring.add(
                        new NettingInput.MonitoringAccount(
                                account.userId(),
                                account.id(),
                                account.externalConnectionId(),
                                account.organization(),
                                sha256(
                                        account.candidateKey()
                                                + "|"
                                                + account.externalAccountKey())));
            }
        monitoring.sort(
                Comparator.comparingLong(NettingInput.MonitoringAccount::userId)
                        .thenComparingLong(NettingInput.MonitoringAccount::accountId));
        var partyIds = new TreeSet<Long>();
        if (predecessor != null) partyIds.addAll(predecessor.partyIds());
        sources.forEach(
                source -> {
                    if (source.debts().isEmpty())
                        throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_SOURCE);
                    source.parties().forEach(party -> partyIds.add(party.userId()));
                });
        var activeMembers =
                members.findActiveByGroupId(groupId).stream()
                        .map(member -> member.userId())
                        .collect(java.util.stream.Collectors.toSet());
        for (long userId : partyIds) {
            if (!activeMembers.contains(userId)
                    || users.findById(userId).filter(user -> user.active()).isEmpty())
                throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_SOURCE);
        }
        var carried =
                predecessor == null
                        ? List.<NettingInput.CarriedTransfer>of()
                        : predecessor.transfers().stream()
                                .map(
                                        t ->
                                                new NettingInput.CarriedTransfer(
                                                        t.id(),
                                                        t.payerId(),
                                                        t.receiverId(),
                                                        t.amount(),
                                                        t.confirmedAmount()))
                                .toList();
        var debtList =
                new ArrayList<com.amateur.howmuchbackend.domain.settlement.netting.NettingDebt>();
        sources.forEach(s -> debtList.addAll(s.debts()));
        if (predecessor != null)
            carried.stream()
                    .filter(t -> t.remainingAmount() > 0)
                    .forEach(
                            t ->
                                    debtList.add(
                                            new com.amateur.howmuchbackend.domain.settlement.netting
                                                    .NettingDebt(
                                                    predecessor.id(),
                                                    t.id(),
                                                    t.payerId(),
                                                    t.receiverId(),
                                                    t.amount(),
                                                    t.confirmedAmount())));
        var decision =
                selection == null
                        ? engine.calculate(debtList)
                        : engine.recalculate(debtList, selection);
        NettingCalculation calculation = decision.calculation();
        var receiverIds =
                calculation.payments().stream()
                        .map(NettingPayment::receiverId)
                        .distinct()
                        .sorted()
                        .toList();
        List<NettingInput.Receiver> receivers = new ArrayList<>();
        for (long userId : receiverIds) {
            var account =
                    accounts.findActiveByUserId(userId).stream()
                            .filter(a -> a.isDefault() && a.isActiveWonDemandDeposit())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            ApplicationException.of(
                                                    NettingStatusCode
                                                            .NETTING_RECEIVING_ACCOUNT_REQUIRED));
            if (!debts.isReceivingAccountConnected(account.id()))
                throw ApplicationException.of(NettingStatusCode.NETTING_RECEIVING_ACCOUNT_REQUIRED);
            receivers.add(
                    new NettingInput.Receiver(
                            userId,
                            account.id(),
                            account.externalConnectionId(),
                            account.organization(),
                            account.maskedNumber(),
                            sha256(account.candidateKey() + "|" + account.externalAccountKey())));
        }
        return new NettingInput(
                groupId,
                group.ownerId(),
                Math.addExact(debts.currentVersion(groupId), 1),
                predecessor == null ? null : predecessor.id(),
                sources,
                carried,
                List.copyOf(partyIds),
                receivers,
                monitoring,
                calculation,
                decision.selection());
    }

    private boolean eligible(NettingInput.Source source, long groupId) {
        long self =
                source.parties().stream()
                        .filter(
                                p ->
                                        Objects.equals(p.userId(), source.creatorId())
                                                && "REGISTERED_USER".equals(p.source())
                                                && p.sourceGroupId() == null)
                        .count();
        for (var party : source.parties()) {
            boolean creator =
                    Objects.equals(party.userId(), source.creatorId())
                            && "REGISTERED_USER".equals(party.source())
                            && party.sourceGroupId() == null;
            if (!creator
                    && (!"GROUP_MEMBER".equals(party.source())
                            || !Objects.equals(party.sourceGroupId(), groupId))) return false;
        }
        if (self != 1 || source.parties().stream().anyMatch(p -> p.userId() == null))
            throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_SOURCE);
        return true;
    }

    public static String fingerprint(NettingInput input) {
        return sha256(input.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
