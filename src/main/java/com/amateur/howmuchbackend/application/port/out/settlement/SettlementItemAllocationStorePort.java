package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementItemAllocationStorePort {
    List<SettlementItemSelection> findAllSelections(UUID settlementId);

    Optional<SettlementItemSelection> findSelection(
            UUID settlementId,
            UUID itemId,
            UUID participantId
    );

    SettlementItemSelection saveSelection(SettlementItemSelection selection);

    void deleteSelection(SettlementItemSelection selection);

    List<SettlementItemSubmission> findAllSubmissions(UUID settlementId);

    Optional<SettlementItemSubmission> findSubmission(
            UUID settlementId,
            UUID participantId
    );

    SettlementItemSubmission saveSubmission(SettlementItemSubmission submission);

    void deleteAllSubmissions(UUID settlementId);
}
