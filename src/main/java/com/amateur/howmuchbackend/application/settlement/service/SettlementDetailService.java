package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementDetailService implements GetSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    private final LoadUserPort loadUserPort;
    private final SettlementReminderCooldownReader cooldownReader;

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
        String creatorName = participants.stream()
                .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                .map(SettlementParticipant::displayName)
                .findFirst()
                .orElseGet(() -> loadUserPort.findById(settlement.creatorId())
                        .map(user -> user.name())
                        .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND)));
        SettlementDetailResult.ViewerRole viewerRole = viewerRole(
                settlement,
                participants,
                actorId
        );

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

        List<SettlementDetailResult.ParticipantPayment> allPayments = participants.stream()
                .filter(participant -> !participant.isLinkedTo(settlement.creatorId()))
                .sorted(Comparator.comparingInt(SettlementParticipant::displayOrder))
                .map(participant -> payment(
                        participant,
                        shareByParticipantId.get(participant.id()),
                        confirmedByShareId
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

        return new SettlementDetailResult(
                settlement.id(),
                settlement.title(),
                settlement.totalAmount(),
                settlement.allocationType(),
                settlement.status(),
                settlement.createdAt(),
                viewerRole,
                creatorName,
                paymentInfo(
                        settlement,
                        loadSettlementExpensePort.findAllBySettlementId(settlementId)
                ),
                summary(settlement.status(), allPayments),
                reminderNextAvailableAt(settlement, viewerRole, allPayments),
                visiblePayments
        );
    }

    private LocalDateTime reminderNextAvailableAt(
            Settlement settlement,
            SettlementDetailResult.ViewerRole viewerRole,
            List<SettlementDetailResult.ParticipantPayment> payments
    ) {
        if (viewerRole != SettlementDetailResult.ViewerRole.CREATOR
                || settlement.status() != SettlementStatus.AWAITING_DEPOSIT) {
            return null;
        }

        List<UUID> reminderCandidateIds = payments.stream()
                .filter(SettlementDetailResult.ParticipantPayment::linked)
                .filter(payment -> payment.paymentStatus()
                        == SettlementDetailResult.PaymentStatus.UNPAID)
                .map(SettlementDetailResult.ParticipantPayment::participantId)
                .toList();
        return cooldownReader.findLatestNextAvailableAt(
                settlement.id(),
                reminderCandidateIds,
                LocalDateTime.now(ZoneOffset.UTC)
        ).orElse(null);
    }

    private SettlementDetailResult.PaymentInfo paymentInfo(
            Settlement settlement,
            List<SettlementExpense> expenses
    ) {
        SettlementReceiptInfo receipt = SettlementReceiptInfo.resolve(expenses);
        return new SettlementDetailResult.PaymentInfo(
                settlement.totalAmount(),
                receipt.storeName(),
                receipt.paidAt()
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
            Map<UUID, Long> confirmedByShareId
    ) {
        if (share == null) {
            return new SettlementDetailResult.ParticipantPayment(
                    participant.id(),
                    participant.displayName(),
                    participant.linkedUserId() != null,
                    null,
                    0,
                    SettlementDetailResult.PaymentStatus.NOT_READY
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
                status
        );
    }

    private SettlementDetailResult.PaymentSummary summary(
            SettlementStatus settlementStatus,
            List<SettlementDetailResult.ParticipantPayment> payments
    ) {
        long expectedAmount = payments.stream()
                .filter(payment -> payment.expectedAmount() != null)
                .mapToLong(SettlementDetailResult.ParticipantPayment::expectedAmount)
                .reduce(0L, Math::addExact);
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
                ? settlementStatus == SettlementStatus.COMPLETED ? 100 : 0
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
}
