package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemSelectionResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementItemAllocationService implements
        GetSettlementItemAllocationUseCase,
        SaveSettlementItemSelectionUseCase,
        RemoveSettlementItemSelectionUseCase,
        SubmitSettlementItemAllocationUseCase {
    private final SettlementLinkTokenPort linkTokenPort;
    private final LoadSettlementLinkPort loadSettlementLinkPort;
    private final LoadSettlementParticipantContextPort loadParticipantContextPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final SettlementItemAllocationStorePort allocationStorePort;
    private final SaveSettlementSharePort saveSettlementSharePort;
    private final SettlementItemAllocationPolicy allocationPolicy;

    @Override
    @Transactional(readOnly = true)
    public SettlementItemAllocationResult get(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Access access = loadAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now,
                false
        );
        return toResult(access, List.of());
    }

    @Override
    @Transactional
    public SettlementItemAllocationResult save(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Access access = loadAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now,
                true
        );
        ensureSelectionEditable(access);
        if (command == null || command.fraction() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_ALLOCATION_FRACTION
            );
        }
        SettlementItem item = findItem(access.items(), itemId);
        List<SettlementItemSelection> selections =
                allocationStorePort.findAllSelections(access.settlement().id());
        allocationPolicy.validateSelection(
                item,
                selections,
                participantId,
                command.fraction()
        );
        SettlementItemSelection selection = allocationStorePort
                .findSelection(access.settlement().id(), item.id(), participantId)
                .map(existing -> existing.changeFraction(command.fraction(), now))
                .orElseGet(() -> SettlementItemSelection.create(
                        access.settlement().id(),
                        item.id(),
                        participantId,
                        command.fraction(),
                        now
                ));
        allocationStorePort.saveSelection(selection);
        return toResult(access, List.of());
    }

    @Override
    @Transactional
    public SettlementItemAllocationResult remove(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            UUID itemId
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Access access = loadAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now,
                true
        );
        ensureSelectionEditable(access);
        findItem(access.items(), itemId);
        SettlementItemSelection selection = allocationStorePort
                .findSelection(access.settlement().id(), itemId, participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_FOUND
                ));
        allocationStorePort.deleteSelection(selection);
        return toResult(access, List.of());
    }

    @Override
    @Transactional
    public SettlementItemAllocationResult submit(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Access access = loadAccess(
                rawLinkToken,
                participantContextToken,
                participantId,
                now,
                true
        );
        ensureSelectionEditable(access);
        allocationStorePort.saveSubmission(SettlementItemSubmission.submit(
                access.settlement().id(),
                access.participant().id(),
                now
        ));

        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(access.settlement().id());
        List<SettlementItemSubmission> submissions = allocationStorePort
                .findAllSubmissions(access.settlement().id());
        if (submissions.size() < participants.size()) {
            return toResult(access, List.of());
        }

        var finalization = allocationPolicy.finalizeAllocation(
                access.settlement().id(),
                access.settlement().totalAmount(),
                participants,
                access.items(),
                allocationStorePort.findAllSelections(access.settlement().id()),
                now
        );
        Settlement updated;
        if (finalization.valid()) {
            saveSettlementSharePort.saveAll(finalization.shares());
            updated = saveSettlementPort.update(access.settlement().awaitDeposit(now));
        } else {
            updated = saveSettlementPort.update(access.settlement().suspend(now));
        }
        return toResult(
                new Access(updated, access.participant(), access.items()),
                finalization.invalidItemIds()
        );
    }

    private Access loadAccess(
            String rawLinkToken,
            UUID participantContextToken,
            UUID participantId,
            LocalDateTime now,
            boolean forUpdate
    ) {
        String linkTokenHash = linkTokenPort.hash(rawLinkToken);
        SettlementLink link = loadSettlementLinkPort.findByTokenHash(linkTokenHash)
                .filter(candidate -> candidate.isUsableAt(now))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        SettlementParticipantContext context = loadParticipantContextPort
                .findByToken(participantContextToken)
                .filter(candidate -> candidate.isUsableAt(now))
                .filter(candidate -> candidate.linkTokenHash().equals(linkTokenHash))
                .filter(candidate -> candidate.settlementId().equals(link.settlementId()))
                .filter(candidate -> candidate.participantId().equals(participantId))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                ));
        Settlement settlement = (forUpdate
                ? loadSettlementPort.findByIdForUpdate(context.settlementId())
                : loadSettlementPort.findById(context.settlementId()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        if (settlement.allocationType() != SettlementAllocationType.ITEM_ALLOCATION) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_ALLOCATION_REQUIRED
            );
        }
        if (settlement.status() != SettlementStatus.REQUESTED
                && settlement.status() != SettlementStatus.AWAITING_DEPOSIT
                && settlement.status() != SettlementStatus.SUSPENDED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
            );
        }
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                ));
        List<SettlementItem> items = loadSettlementItemPort
                .findAllBySettlementId(settlement.id());
        if (items.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ITEM_REQUIRED);
        }
        return new Access(settlement, participant, items);
    }

    private void ensureSelectionEditable(Access access) {
        if (access.settlement().status() != SettlementStatus.REQUESTED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE
            );
        }
        if (allocationStorePort.findSubmission(
                access.settlement().id(),
                access.participant().id()
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

    private SettlementItemAllocationResult toResult(
            Access access,
            List<UUID> invalidItemIds
    ) {
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(access.settlement().id());
        Map<UUID, SettlementParticipant> participantById = new HashMap<>();
        participants.forEach(participant -> participantById.put(
                participant.id(), participant
        ));
        List<SettlementItemSelection> selections = allocationStorePort
                .findAllSelections(access.settlement().id());
        List<SettlementItemSubmission> submissions = allocationStorePort
                .findAllSubmissions(access.settlement().id());
        SettlementItemAllocationPhase phase = resolvePhase(
                access.settlement(),
                submissions,
                access.participant().id()
        );
        List<SettlementItemAllocationItemResult> itemResults = access.items().stream()
                .map(item -> toItemResult(item, selections, participantById))
                .toList();
        return new SettlementItemAllocationResult(
                access.settlement().id(),
                access.participant().id(),
                access.participant().displayName(),
                phase,
                allocationPolicy.calculatePersonalAmount(
                        access.items(), selections, access.participant().id()
                ),
                submissions.size(),
                participants.size(),
                itemResults,
                invalidItemIds
        );
    }

    private SettlementItemAllocationItemResult toItemResult(
            SettlementItem item,
            List<SettlementItemSelection> selections,
            Map<UUID, SettlementParticipant> participantById
    ) {
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
                            selection.amountFor(item)
                    );
                })
                .toList();
        int allocatedUnits = selectionResults.stream()
                .mapToInt(selection -> selection.fraction().units())
                .sum();
        return new SettlementItemAllocationItemResult(
                item.id(),
                item.name(),
                item.quantity(),
                item.amount(),
                allocatedUnits,
                SettlementItemAllocationFraction.WHOLE_UNITS,
                selectionResults
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

    private record Access(
            Settlement settlement,
            SettlementParticipant participant,
            List<SettlementItem> items
    ) {
        private Access {
            items = List.copyOf(items);
        }
    }
}
