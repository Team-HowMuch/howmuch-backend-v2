package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementItemAllocationWriter {
    private final SettlementItemAllocationStateLoader stateLoader;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementItemAllocationStorePort allocationStorePort;
    private final SaveSettlementSharePort saveSettlementSharePort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementItemAllocationPolicy allocationPolicy;

    @Transactional
    public void save(
            UUID settlementId,
            UUID participantId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command,
            LocalDateTime now
    ) {
        var state = stateLoader.load(settlementId, participantId, true);
        ensureSelectionEditable(state);
        if (command == null || command.fraction() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_ALLOCATION_FRACTION
            );
        }
        SettlementItem item = findItem(state.items(), itemId);
        List<SettlementItemSelection> selections =
                allocationStorePort.findAllSelections(settlementId);
        allocationPolicy.validateSelection(
                item,
                selections,
                participantId,
                command.fraction()
        );
        SettlementItemSelection selection = allocationStorePort
                .findSelection(settlementId, item.id(), participantId)
                .map(existing -> existing.changeFraction(command.fraction(), now))
                .orElseGet(() -> SettlementItemSelection.create(
                        settlementId,
                        item.id(),
                        participantId,
                        command.fraction(),
                        now
                ));
        allocationStorePort.saveSelection(selection);
    }

    @Transactional
    public void remove(UUID settlementId, UUID participantId, UUID itemId) {
        var state = stateLoader.load(settlementId, participantId, true);
        ensureSelectionEditable(state);
        findItem(state.items(), itemId);
        SettlementItemSelection selection = allocationStorePort
                .findSelection(settlementId, itemId, participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_FOUND
                ));
        allocationStorePort.deleteSelection(selection);
    }

    @Transactional
    public void submit(UUID settlementId, UUID participantId, LocalDateTime now) {
        var state = stateLoader.load(settlementId, participantId, true);
        ensureSelectionEditable(state);
        allocationStorePort.saveSubmission(SettlementItemSubmission.submit(
                settlementId,
                participantId,
                now
        ));

        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlementId);
        List<SettlementItemSubmission> submissions = allocationStorePort
                .findAllSubmissions(settlementId);
        if (submissions.size() < participants.size()) {
            return;
        }

        var finalization = allocationPolicy.finalizeAllocation(
                settlementId,
                state.settlement().totalAmount(),
                participants,
                state.items(),
                allocationStorePort.findAllSelections(settlementId),
                now
        );
        if (finalization.valid()) {
            saveSettlementSharePort.saveAll(finalization.shares());
            saveSettlementPort.update(state.settlement().awaitDeposit(now));
            return;
        }
        saveSettlementPort.update(state.settlement().suspend(now));
    }

    private void ensureSelectionEditable(SettlementItemAllocationStateLoader.State state) {
        if (state.settlement().status() != SettlementStatus.REQUESTED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE
            );
        }
        if (allocationStorePort.findSubmission(
                state.settlement().id(),
                state.participant().id()
        ).isPresent()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS
            );
        }
    }

    private SettlementItem findItem(List<SettlementItem> items, UUID itemId) {
        if (itemId == null) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ITEM_NOT_FOUND);
        }
        return items.stream()
                .filter(item -> item.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ITEM_NOT_FOUND
                ));
    }
}
