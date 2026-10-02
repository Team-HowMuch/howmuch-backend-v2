package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemSelectionResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementItemAllocationReader {
    private final SettlementItemAllocationStateLoader stateLoader;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementItemAllocationStorePort allocationStorePort;
    private final SettlementItemAllocationPolicy allocationPolicy;

    @Transactional(readOnly = true)
    public SettlementItemAllocationResult read(
            UUID settlementId,
            UUID participantId,
            SettlementItemAllocationEntry entry
    ) {
        var state = stateLoader.load(settlementId, participantId, false, entry);
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlementId);
        Map<UUID, SettlementParticipant> participantById = new HashMap<>();
        participants.forEach(participant -> participantById.put(
                participant.id(), participant
        ));
        List<SettlementItemSelection> selections = allocationStorePort
                .findAllSelections(settlementId);
        List<SettlementItemSubmission> submissions = allocationStorePort
                .findAllSubmissions(settlementId);
        List<UUID> invalidItemIds = state.settlement().status() == SettlementStatus.SUSPENDED
                ? allocationPolicy.findInvalidItemIds(
                        settlementId,
                        state.settlement().totalAmount(),
                        participants,
                        state.items(),
                        selections,
                        state.receiptDiscount(),
                        state.deliveryFee()
                )
                : List.of();
        SettlementItemAllocationPhase phase = resolvePhase(
                state.settlement(),
                submissions,
                participantId
        );
        List<SettlementItemAllocationItemResult> itemResults = state.items().stream()
                .map(item -> toItemResult(item, selections, participantById))
                .toList();
        boolean finalized = state.settlement().status() == SettlementStatus.AWAITING_DEPOSIT;
        return new SettlementItemAllocationResult(
                settlementId,
                participantId,
                state.participant().displayName(),
                phase,
                allocationPolicy.calculatePersonalAmount(
                        state.items(), selections, participantId, state.receiptDiscount(),
                        state.deliveryFee(), finalized
                ),
                submissions.size(),
                participants.size(),
                itemResults,
                invalidItemIds,
                state.receiptDiscount(),
                allocationPolicy.calculateDiscountShare(
                        state.items(), selections, participantId, state.receiptDiscount()
                ),
                state.deliveryFee(),
                allocationPolicy.calculateDeliveryFeeShare(
                        state.items(), selections, participantId, state.deliveryFee(), finalized
                )
        );
    }

    private SettlementItemAllocationItemResult toItemResult(
            SettlementItem item,
            List<SettlementItemSelection> selections,
            Map<UUID, SettlementParticipant> participantById
    ) {
        Map<UUID, Long> amountBySelection = allocationPolicy.calculateSelectionAmounts(
                item, selections
        );
        List<SettlementItemSelectionResult> selectionResults = selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .sorted(Comparator.comparingInt(selection -> {
                    SettlementParticipant participant = participantById.get(
                            selection.participantId()
                    );
                    return participant == null
                            ? Integer.MAX_VALUE
                            : participant.displayOrder();
                }))
                .map(selection -> {
                    SettlementParticipant participant = participantById.get(
                            selection.participantId()
                    );
                    if (participant == null) {
                        throw ApplicationException.of(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
                        );
                    }
                    return new SettlementItemSelectionResult(
                            participant.id(),
                            participant.displayName(),
                            selection.fraction(),
                            selection.units(),
                            amountBySelection.getOrDefault(selection.id(), 0L)
                    );
                })
                .toList();
        int allocatedUnits = selectionResults.stream()
                .mapToInt(selection -> Math.multiplyExact(
                        selection.fraction().units(), selection.units()
                ))
                .sum();
        return new SettlementItemAllocationItemResult(
                item.id(),
                item.name(),
                item.quantity(),
                item.amount(),
                allocatedUnits,
                item.totalAllocationUnits(),
                selectionResults,
                item.discount(),
                item.options().stream()
                        .map(com.amateur.howmuchbackend.application.port.in.settlement.result
                                .SettlementItemOptionResult::from)
                        .toList()
        );
    }

    private SettlementItemAllocationPhase resolvePhase(
            Settlement settlement,
            List<SettlementItemSubmission> submissions,
            UUID participantId
    ) {
        if (settlement.status() == SettlementStatus.AWAITING_DEPOSIT) {
            return SettlementItemAllocationPhase.TRANSFER_READY;
        }
        if (settlement.status() == SettlementStatus.SUSPENDED) {
            return SettlementItemAllocationPhase.SUSPENDED;
        }
        boolean submitted = submissions.stream()
                .anyMatch(submission -> submission.participantId().equals(participantId));
        return submitted
                ? SettlementItemAllocationPhase.WAITING_FOR_OTHERS
                : SettlementItemAllocationPhase.SELECTING;
    }
}
