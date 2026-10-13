package com.amateur.howmuchbackend.application.recap.model;

import com.amateur.howmuchbackend.domain.settlement.*;
import java.util.List;
import java.util.UUID;

public record RecapAllocationFacts(UUID ownParticipantId, boolean ownSubmitted, int submissionCount,
        long settlementTotalAmount, long receiptDiscount, long deliveryFee,
        List<SettlementParticipant> participants, List<SettlementItem> items, List<SettlementItemSelection> selections) {}
