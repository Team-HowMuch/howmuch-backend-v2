package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDutchPayAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementAllocationTypeUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDutchPayAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementAllocationTypeCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationTypeResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.DutchPayAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementAllocationService implements
        SelectSettlementAllocationTypeUseCase,
        GetSettlementDutchPayAllocationUseCase,
        UpdateSettlementDutchPayAllocationUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final SettlementDutchPayAllocationStorePort allocationStorePort;
    private final DutchPayAllocationPolicy dutchPayAllocationPolicy;

    @Override
    @Transactional
    public SettlementAllocationTypeResult select(
            UUID settlementId,
            SelectSettlementAllocationTypeCommand command
    ) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureAllocationEditable();
        SettlementAllocationType selectedType = command == null ? null : command.allocationType();
        if (selectedType == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ALLOCATION_TYPE
            );
        }
        if (selectedType == SettlementAllocationType.ITEM_ALLOCATION) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_ALLOCATION_NOT_AVAILABLE
            );
        }
        List<SettlementExpense> expenses = loadSettlementExpensePort
                .findAllBySettlementId(settlement.id());
        long expenseTotal = validateExpenseTotal(settlement, expenses);

        if (selectedType == settlement.allocationType()) {
            ensureExistingSelectionIsUsable(settlement, selectedType);
            return SettlementAllocationTypeResult.of(settlement.id(), selectedType);
        }

        Settlement selected = settlement.selectAllocationType(selectedType, now());
        saveSettlementPort.update(selected);
        if (selectedType == SettlementAllocationType.DUTCH_PAY) {
            List<SettlementParticipant> participants = loadSettlementParticipantPort
                    .findAllBySettlementId(settlement.id());
            allocationStorePort.replaceAll(
                    settlement.id(),
                    dutchPayAllocationPolicy.calculate(
                            settlement,
                            participants,
                            expenseTotal,
                            now()
                    )
            );
        } else {
            allocationStorePort.deleteAllBySettlementId(settlement.id());
        }
        return SettlementAllocationTypeResult.of(settlement.id(), selectedType);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementDutchPayResult get(UUID settlementId) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureAllocationEditable();
        ensureDutchPay(settlement);
        return assembleResult(settlement);
    }

    @Override
    @Transactional
    public SettlementDutchPayResult update(
            UUID settlementId,
            UUID participantId,
            UpdateSettlementDutchPayAllocationCommand command
    ) {
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId);
        settlement.ensureAllocationEditable();
        ensureDutchPay(settlement);
        loadSettlementParticipantPort.findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        SettlementDutchPayAllocation allocation = allocationStorePort
                .findBySettlementIdAndParticipantId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_NOT_FOUND
                ));
        long amount = command == null ? -1L : command.amount();
        allocationStorePort.update(allocation.updateAmount(amount, now()));
        return assembleResult(settlement);
    }

    private void ensureExistingSelectionIsUsable(
            Settlement settlement,
            SettlementAllocationType selectedType
    ) {
        if (selectedType != SettlementAllocationType.DUTCH_PAY) {
            return;
        }
        int participantCount = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id())
                .size();
        int allocationCount = allocationStorePort
                .findAllBySettlementId(settlement.id())
                .size();
        if (participantCount != allocationCount) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
            );
        }
    }

    private long validateExpenseTotal(
            Settlement settlement,
            List<SettlementExpense> expenses
    ) {
        if (expenses.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_EXPENSE_REQUIRED);
        }
        long total = 0L;
        try {
            for (SettlementExpense expense : expenses) {
                total = Math.addExact(total, expense.amount());
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                    exception
            );
        }
        if (total != settlement.totalAmount()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_EXPENSE_TOTAL_MISMATCH
            );
        }
        return total;
    }

    private SettlementDutchPayResult assembleResult(Settlement settlement) {
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id());
        Map<UUID, SettlementDutchPayAllocation> allocationByParticipant = new HashMap<>();
        for (SettlementDutchPayAllocation allocation
                : allocationStorePort.findAllBySettlementId(settlement.id())) {
            allocationByParticipant.put(allocation.participantId(), allocation);
        }
        if (allocationByParticipant.size() != participants.size()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
            );
        }

        long allocatedAmount = 0L;
        List<SettlementDutchPayParticipantResult> participantResults = new java.util.ArrayList<>();
        try {
            for (SettlementParticipant participant : participants) {
                SettlementDutchPayAllocation allocation = allocationByParticipant
                        .get(participant.id());
                if (allocation == null) {
                    throw ApplicationException.of(
                            SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
                    );
                }
                allocatedAmount = Math.addExact(allocatedAmount, allocation.amount());
                participantResults.add(new SettlementDutchPayParticipantResult(
                        participant.id(),
                        participant.displayName(),
                        allocation.amount(),
                        participant.isLinkedTo(settlement.creatorId())
                ));
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_DUTCH_PAY_TOTAL,
                    exception
            );
        }
        long differenceAmount = settlement.totalAmount() - allocatedAmount;
        return new SettlementDutchPayResult(
                settlement.id(),
                settlement.totalAmount(),
                allocatedAmount,
                differenceAmount,
                differenceAmount == 0L,
                List.copyOf(participantResults)
        );
    }

    private void ensureDutchPay(Settlement settlement) {
        if (settlement.allocationType() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ALLOCATION_NOT_SELECTED
            );
        }
        if (settlement.allocationType() != SettlementAllocationType.DUTCH_PAY) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_DUTCH_PAY_REQUIRED);
        }
    }

    private Settlement loadOwnedSettlement(UUID settlementId) {
        return verifyOwnership(loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )));
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId) {
        return verifyOwnership(loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )));
    }

    private Settlement verifyOwnership(Settlement settlement) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        if (!settlement.isCreatedBy(actor.userId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
