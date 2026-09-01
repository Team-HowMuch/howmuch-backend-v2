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

    State load(
            UUID settlementId,
            UUID participantId,
            boolean forUpdate,
            SettlementItemAllocationEntry entry
    ) {
        // 링크 경로는 정산의 존재 여부까지 감춰야 해서 링크 오류로 통일한다.
        SettlementStatusCode notFoundCode = entry == SettlementItemAllocationEntry.LINK
                ? SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                : SettlementStatusCode.SETTLEMENT_NOT_FOUND;
        Settlement settlement = (forUpdate
                ? loadSettlementPort.findByIdForUpdate(settlementId)
                : loadSettlementPort.findById(settlementId))
                .orElseThrow(() -> ApplicationException.of(notFoundCode));
        if (settlement.allocationType() != SettlementAllocationType.ITEM_ALLOCATION) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_ALLOCATION_REQUIRED
            );
        }
        if (!isReadableStatus(settlement.status(), entry)) {
            throw ApplicationException.of(notFoundCode);
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

    /** 생성자는 알림 발송 전 DRAFT에서도 자기 몫을 봐야 한다. 링크는 요청 이후만 본다. */
    private boolean isReadableStatus(
            SettlementStatus status,
            SettlementItemAllocationEntry entry
    ) {
        if (entry == SettlementItemAllocationEntry.CREATOR
                && status == SettlementStatus.DRAFT) {
            return true;
        }
        return status == SettlementStatus.REQUESTED
                || status == SettlementStatus.AWAITING_DEPOSIT
                || status == SettlementStatus.SUSPENDED;
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
