package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementDetailService implements GetSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final SettlementItemAllocationStorePort itemAllocationStorePort;
    private final LoadSettlementReceivingAccountSnapshotPort loadReceivingAccountSnapshotPort;
    private final LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    private final LoadUserPort loadUserPort;
    private final SettlementReminderCooldownReader cooldownReader;
    private final BankCatalog bankCatalog;
    private final SettlementItemAllocationPolicy itemAllocationPolicy;

    @Override
    @Transactional(readOnly = true)
    public SettlementDetailResult get(UUID settlementId) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        List<SettlementParticipant> participants =
                loadSettlementParticipantPort.findAllBySettlementId(settlementId);
        SettlementParticipant creatorParticipant = participants.stream()
                .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                .findFirst()
                .orElse(null);
        String creatorName = creatorParticipant == null
                ? loadUserPort.findById(settlement.creatorId())
                        .map(User::name)
                        .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND))
                : creatorParticipant.displayName();
        SettlementDetailResult.ViewerRole viewerRole = viewerRole(
                settlement,
                participants,
                actorId
        );
        Set<UUID> submittedParticipantIds = itemAllocationStorePort.findAllSubmissions(settlementId).stream()
                .map(submission -> submission.participantId())
                .collect(Collectors.toSet());
        List<SettlementExpense> expenses = loadSettlementExpensePort.findAllBySettlementId(settlementId);
        long receiptDiscount = expenses.stream().mapToLong(SettlementExpense::discount)
                .reduce(0L, Math::addExact);
        long deliveryFee = expenses.stream().mapToLong(SettlementExpense::deliveryFee)
                .reduce(0L, Math::addExact);
        List<SettlementItem> items = loadSettlementItemPort.findAllBySettlementId(settlementId);
        List<SettlementItemSelection> selections =
                settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                        ? itemAllocationStorePort.findAllSelections(settlementId) : List.of();
        Map<UUID, SettlementShare> shareByParticipantId = loadSettlementSharePort
                .findAllBySettlementId(settlementId)
                .stream()
                .collect(Collectors.toMap(
                        SettlementShare::payerParticipantId,
                        Function.identity()
                ));
        Map<UUID, Long> confirmedByShareId = loadDepositSummaryPort
                .findConfirmedShareAmountsBySettlementId(settlementId)
                .stream()
                .collect(Collectors.toMap(
                        LoadSettlementDepositSummaryPort.ConfirmedShareAmount::shareId,
                        LoadSettlementDepositSummaryPort.ConfirmedShareAmount::amount
                ));
        boolean receiptAdjustmentPending = settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                && (receiptDiscount > 0 || deliveryFee > 0)
                && settlement.status() != SettlementStatus.AWAITING_DEPOSIT
                && settlement.status() != SettlementStatus.COMPLETED;

        Set<Long> visibleUserIds = participants.stream()
                .filter(participant -> !participant.isLinkedTo(settlement.creatorId()))
                .filter(participant -> viewerRole == SettlementDetailResult.ViewerRole.CREATOR
                        || participant.isLinkedTo(actorId))
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> avatarVersionsByUserId = visibleUserIds.isEmpty()
                ? Map.of()
                : loadUserPort.findAllByIds(visibleUserIds).stream()
                        .filter(user -> user.avatarFileName() != null)
                        .collect(Collectors.toMap(
                                User::id,
                                user -> ImageCacheVersion.fromNullableFileName(user.avatarFileName())
                        ));

        List<SettlementDetailResult.ParticipantPayment> allPayments = participants.stream()
                .filter(participant -> !participant.isLinkedTo(settlement.creatorId()))
                .sorted(Comparator.comparingInt(SettlementParticipant::displayOrder))
                .map(participant -> payment(
                        participant,
                        shareByParticipantId.get(participant.id()),
                        confirmedByShareId,
                        participant.linkedUserId() == null
                                ? null
                                : avatarVersionsByUserId.get(participant.linkedUserId()),
                        submittedParticipantIds.contains(participant.id()),
                        receiptDiscount == 0 ? 0 : itemAllocationPolicy.calculateDiscountShare(
                                items, selections, participant.id(), receiptDiscount
                        ),
                        settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                                ? itemAllocationPolicy.calculateDeliveryFeeShare(items, selections,
                                        participant.id(), deliveryFee, !receiptAdjustmentPending) : 0,
                        receiptAdjustmentPending
                ))
                .toList();
        UUID viewerParticipantId = viewerRole == SettlementDetailResult.ViewerRole.PARTICIPANT
                ? participants.stream()
                        .filter(participant -> participant.isLinkedTo(actorId))
                        .map(SettlementParticipant::id)
                        .findFirst()
                        .orElseThrow(() -> ApplicationException.of(
                                SettlementStatusCode.SETTLEMENT_ACCESS_DENIED
                        ))
                : null;
        List<SettlementDetailResult.ParticipantPayment> visiblePayments =
                viewerRole == SettlementDetailResult.ViewerRole.CREATOR
                        ? allPayments
                        : allPayments.stream()
                                .filter(payment -> payment.participantId().equals(viewerParticipantId))
                                .toList();
        String bankName = null;
        String accountNumber = null;
        String institutionIconUrl = null;
        if (viewerRole == SettlementDetailResult.ViewerRole.CREATOR
                && settlement.status() != SettlementStatus.DRAFT) {
            SettlementReceivingAccountSnapshot snapshot = loadReceivingAccountSnapshotPort
                    .findBySettlementId(settlementId)
                    .orElseThrow(() -> ApplicationException.of(
                            SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                    ));
            BankCatalogResult institution = bankCatalog.getAllBanks().stream()
                    .filter(bank -> bank.organization().equals(snapshot.organization()))
                    .findFirst()
                    .orElse(null);
            bankName = institution == null || institution.name() == null
                    || institution.name().isBlank()
                    ? snapshot.organization()
                    : institution.name();
            accountNumber = snapshot.accountNumber();
            institutionIconUrl = institution == null ? null : institution.iconUrl();
        }

        return new SettlementDetailResult(
                settlement.id(),
                settlement.title(),
                settlement.totalAmount(),
                settlement.allocationType(),
                settlement.status(),
                settlement.createdAt(),
                endedAt(settlement),
                viewerRole,
                creatorName,
                bankName,
                accountNumber,
                institutionIconUrl,
                paymentInfo(
                        settlement,
                        expenses
                ),
                summary(settlement, creatorParticipant, allPayments, receiptDiscount, deliveryFee),
                reminderNextAvailableAt(
                        settlement, viewerRole, allPayments, submittedParticipantIds
                ),
                visiblePayments,
                items.stream().map(com.amateur.howmuchbackend.application.port.in.settlement.result
                        .SettlementItemResult::from).toList()
        );
    }

    private LocalDateTime endedAt(Settlement settlement) {
        return switch (settlement.status()) {
            case COMPLETED, SUSPENDED -> settlement.updatedAt();
            default -> null;
        };
    }

    private LocalDateTime reminderNextAvailableAt(
            Settlement settlement,
            SettlementDetailResult.ViewerRole viewerRole,
            List<SettlementDetailResult.ParticipantPayment> payments,
            Set<UUID> submittedParticipantIds
    ) {
        if (viewerRole != SettlementDetailResult.ViewerRole.CREATOR
                || (settlement.status() != SettlementStatus.REQUESTED
                && settlement.status() != SettlementStatus.AWAITING_DEPOSIT)) {
            return null;
        }

        List<UUID> reminderCandidateIds = payments.stream()
                .filter(SettlementDetailResult.ParticipantPayment::linked)
                .filter(payment -> isReminderCandidate(
                        settlement.status(), payment, submittedParticipantIds
                ))
                .map(SettlementDetailResult.ParticipantPayment::participantId)
                .toList();
        return cooldownReader.findLatestNextAvailableAt(
                settlement.id(),
                reminderCandidateIds
        ).orElse(null);
    }

    private boolean isReminderCandidate(
            SettlementStatus status,
            SettlementDetailResult.ParticipantPayment payment,
            Set<UUID> submittedParticipantIds
    ) {
        return status == SettlementStatus.REQUESTED
                ? !submittedParticipantIds.contains(payment.participantId())
                : payment.paymentStatus() == SettlementDetailResult.PaymentStatus.UNPAID;
    }

    private SettlementDetailResult.PaymentInfo paymentInfo(
            Settlement settlement,
            List<SettlementExpense> expenses
    ) {
        SettlementPaymentInfo payment = SettlementPaymentInfo.resolve(expenses);
        return new SettlementDetailResult.PaymentInfo(
                settlement.totalAmount(),
                payment.storeName(),
                payment.paidAt(),
                expenses.stream().mapToLong(SettlementExpense::discount).reduce(0L, Math::addExact),
                expenses.stream().mapToLong(SettlementExpense::deliveryFee).reduce(0L, Math::addExact)
        );
    }

    private SettlementDetailResult.ViewerRole viewerRole(
            Settlement settlement,
            List<SettlementParticipant> participants,
            Long actorId
    ) {
        if (settlement.isCreatedBy(actorId)) {
            return SettlementDetailResult.ViewerRole.CREATOR;
        }
        if (participants.stream().anyMatch(participant -> participant.isLinkedTo(actorId))) {
            return SettlementDetailResult.ViewerRole.PARTICIPANT;
        }
        throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
    }

    private SettlementDetailResult.ParticipantPayment payment(
            SettlementParticipant participant,
            SettlementShare share,
            Map<UUID, Long> confirmedByShareId,
            String avatarVersion,
            boolean submitted,
            long discountShare,
            long deliveryFeeShare,
            boolean receiptAdjustmentPending
    ) {
        if (share == null) {
            return new SettlementDetailResult.ParticipantPayment(
                    participant.id(),
                    participant.displayName(),
                    participant.linkedUserId() != null,
                    submitted && !receiptAdjustmentPending ? 0L : null,
                    0,
                    submitted && !receiptAdjustmentPending
                            ? SettlementDetailResult.PaymentStatus.PAID
                            : SettlementDetailResult.PaymentStatus.NOT_READY,
                    submitted,
                    avatarVersion,
                    discountShare,
                    deliveryFeeShare
            );
        }
        long confirmedAmount = confirmedByShareId.getOrDefault(share.id(), 0L);
        SettlementDetailResult.PaymentStatus status = confirmedAmount == 0
                ? SettlementDetailResult.PaymentStatus.UNPAID
                : confirmedAmount < share.amount()
                        ? SettlementDetailResult.PaymentStatus.PARTIALLY_PAID
                        : SettlementDetailResult.PaymentStatus.PAID;
        return new SettlementDetailResult.ParticipantPayment(
                participant.id(),
                participant.displayName(),
                participant.linkedUserId() != null,
                share.amount(),
                confirmedAmount,
                status,
                submitted,
                avatarVersion,
                discountShare,
                deliveryFeeShare
        );
    }

    private SettlementDetailResult.PaymentSummary summary(
            Settlement settlement,
            SettlementParticipant creatorParticipant,
            List<SettlementDetailResult.ParticipantPayment> payments,
            long receiptDiscount,
            long deliveryFee
    ) {
        long shareExpectedAmount = payments.stream()
                .filter(payment -> payment.expectedAmount() != null)
                .mapToLong(SettlementDetailResult.ParticipantPayment::expectedAmount)
                .reduce(0L, Math::addExact);
        long expectedAmount = settlement.allocationType()
                == SettlementAllocationType.ITEM_ALLOCATION
                && (settlement.status() == SettlementStatus.REQUESTED
                        || settlement.status() == SettlementStatus.SUSPENDED)
                ? requestedItemExpectedAmount(settlement, creatorParticipant, receiptDiscount, deliveryFee)
                : shareExpectedAmount;
        long confirmedAmount = payments.stream()
                .filter(payment -> payment.expectedAmount() != null)
                .mapToLong(payment -> Math.min(
                        payment.confirmedAmount(),
                        payment.expectedAmount()
                ))
                .reduce(0L, Math::addExact);
        int completedCount = Math.toIntExact(payments.stream()
                .filter(payment -> payment.paymentStatus()
                        == SettlementDetailResult.PaymentStatus.PAID)
                .count());
        int progressPercent = expectedAmount == 0
                ? settlement.status() == SettlementStatus.COMPLETED ? 100 : 0
                : BigInteger.valueOf(confirmedAmount)
                        .multiply(BigInteger.valueOf(100))
                        .divide(BigInteger.valueOf(expectedAmount))
                        .min(BigInteger.valueOf(100))
                        .intValueExact();
        return new SettlementDetailResult.PaymentSummary(
                expectedAmount,
                confirmedAmount,
                expectedAmount - confirmedAmount,
                progressPercent,
                // payments는 생성자를 이미 제외한 목록이라 두 값이 같다.
                // 목록 API의 participantCount(생성자 포함)와 이름을 맞추기 위해 둘 다 노출한다.
                payments.size(),
                payments.size(),
                completedCount
        );
    }

    private long requestedItemExpectedAmount(
            Settlement settlement,
            SettlementParticipant creatorParticipant,
            long receiptDiscount,
            long deliveryFee
    ) {
        if (creatorParticipant == null) {
            return settlement.totalAmount();
        }
        return itemAllocationPolicy.calculateRequestedReceivableAmount(
                settlement.totalAmount(),
                loadSettlementItemPort.findAllBySettlementId(settlement.id()),
                itemAllocationStorePort.findAllSelections(settlement.id()),
                creatorParticipant.id(),
                receiptDiscount,
                deliveryFee
        );
    }
}
