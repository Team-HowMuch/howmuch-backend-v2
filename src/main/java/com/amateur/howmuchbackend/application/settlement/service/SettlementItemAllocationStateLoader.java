package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementItemAllocationStateLoader {
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementItemPort loadSettlementItemPort;

    State load(UUID settlementId, UUID participantId, boolean forUpdate) {
        Settlement settlement = (forUpdate
                ? loadSettlementPort.findByIdForUpdate(settlementId)
                : loadSettlementPort.findById(settlementId))
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
        return new State(settlement, participant, items);
    }

    record State(
            Settlement settlement,
            SettlementParticipant participant,
            List<SettlementItem> items
    ) {
        State {
            items = List.copyOf(items);
        }
    }
}
