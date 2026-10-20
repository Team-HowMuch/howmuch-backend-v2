package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;

public record RecurringSettlementPresentationResult(List<Participant> participants, ReceivingAccount receivingAccount) {
    public RecurringSettlementPresentationResult {
        participants = List.copyOf(participants);
    }

    public record Participant(Long userId, String name, long amount, int displayOrder,
            Long memberId, String avatarVersion, boolean self) {}
    public record ReceivingAccount(Long id, String bankName, String maskedNumber) {}
}
