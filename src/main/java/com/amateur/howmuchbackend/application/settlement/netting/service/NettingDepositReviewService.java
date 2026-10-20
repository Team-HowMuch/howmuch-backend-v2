package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.ResolveNettingDepositReviewUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.command.ResolveNettingReviewCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingReviewResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import com.amateur.howmuchbackend.domain.user.UserRole;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingDepositReviewService implements ResolveNettingDepositReviewUseCase {
    private final CurrentActorPort actor;
    private final LoadUserPort users;
    private final LoadGroupPort groups;
    private final NettingDepositStorePort reviews;
    private final LoadGroupNettingPort nettings;
    private final SaveGroupNettingPort saveNettings;
    private final LoadGroupNettingDebtPort locks;
    private final LoadAccountTransactionPort transactions;
    private final NettedDepositCommitWriter completion;
    private final NettingEstimateInvalidationService estimateInvalidation;

    @Transactional(readOnly = true)
    public List<NettingReviewResult> list(UUID id) {
        requireAdmin();
        return reviews.findReviews(id).stream().map(this::result).toList();
    }

    @Transactional
    public NettingReviewResult resolve(UUID id, ResolveNettingReviewCommand command) {
        long admin = requireAdmin();
        var review =
                reviews.findReview(id)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        var initial = nettings.findById(review.nettingId()).orElseThrow();
        locks.lockPaymentInputs(initial);
        groups.findByIdForUpdate(initial.groupId()).orElseThrow();
        saveNettings.lockNetting(initial.id());
        review = reviews.findReview(id).orElseThrow();
        if (!"OPEN".equals(review.status())
                || command.proof() == null
                || command.proof().isBlank()
                || command.proof().length() > 1000)
            throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        switch (command.decision()) {
            case "APPLIED_TO_NETTING" -> {
                var current = nettings.findById(initial.id()).orElseThrow();
                var selected =
                        current.transfers().stream()
                                .filter(t -> t.id().equals(command.transferId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                ApplicationException.of(
                                                        NettingStatusCode.NETTING_INVALID_REVIEW));
                var transaction =
                        transactions
                                .findByAccountIdAndId(review.accountId(), review.transactionId())
                                .orElseThrow();
                long amount;
                try {
                    amount = transaction.amount().longValueExact();
                } catch (ArithmeticException invalid) {
                    throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
                }
                if (!"ACTIVE".equals(current.status())
                        || selected.receiverId() != review.receiverId()
                        || !nettings.monitoredAccounts(current.id(), review.receiverId()).stream()
                                .map(LoadGroupNettingPort.ReceivingAccount::accountId)
                                .toList()
                                .contains(review.accountId())
                        || transaction.transactedAt().isBefore(selected.activatedAt())
                        || amount <= 0
                        || amount > selected.remainingAmount())
                    throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
                reviews.applyReviewed(review, selected, admin, command.proof().trim(), now);
            }
            case "UNRELATED", "RETURNED" -> {
                if (command.transferId() != null)
                    throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
                reviews.resolveUnrelatedOrReturned(
                        review, command.decision(), admin, command.proof().trim(), now);
            }
            default -> throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
        }
        completion.complete(initial, now);
        estimateInvalidation.groupChanged(initial.groupId());
        return result(reviews.findReview(id).orElseThrow());
    }

    private long requireAdmin() {
        long id = actor.getCurrentActor().userId();
        if (users.findById(id).filter(u -> u.active() && u.role() == UserRole.ADMIN).isEmpty())
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        return id;
    }

    private NettingReviewResult result(NettingDepositStorePort.Review r) {
        return new NettingReviewResult(
                r.id(),
                r.nettingId(),
                r.receiverId(),
                r.transactionId(),
                r.reason(),
                r.status(),
                r.proof(),
                r.resolvedBy(),
                r.createdAt(),
                r.resolvedAt());
    }
}
