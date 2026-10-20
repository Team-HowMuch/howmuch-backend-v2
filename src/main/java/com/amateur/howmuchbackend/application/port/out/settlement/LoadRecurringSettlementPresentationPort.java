package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Batch display facts for authorized recurring templates, without live account secrets. */
public interface LoadRecurringSettlementPresentationPort {
    Map<UUID, Presentation> loadAll(Collection<UUID> ids, Long viewerId);

    record Presentation(List<Participant> participants, ReceivingAccount receivingAccount) {}
    record Participant(Long userId, String name, long amount, int displayOrder, Long memberId, String avatarFileName) {}
    record ReceivingAccount(Long id, String bankName, String maskedNumber) {}
}
