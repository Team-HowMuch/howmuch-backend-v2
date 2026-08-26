package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
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
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final SettlementDepositStorePort depositStorePort;

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
        SettlementParticipant creator = participants.stream()
                .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND
                ));
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
        Map<UUID, Long> confirmedByShareId = depositStorePort
                .findConfirmedShareAmountsBySettlementId(settlementId)
                .stream()
                .collect(Collectors.toMap(
                        SettlementDepositStorePort.ConfirmedShareAmount::shareId,
                        SettlementDepositStorePort.ConfirmedShareAmount::amount
                ));

        List<SettlementDetailResult.ParticipantPayment> allPayments = participants.stream()
                .filter(participant -> !participant.id().equals(creator.id()))
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
                creator.displayName(),
                summary(settlement.status(), allPayments),
                visiblePayments
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
                payments.size(),
                completedCount
        );
    }
}
