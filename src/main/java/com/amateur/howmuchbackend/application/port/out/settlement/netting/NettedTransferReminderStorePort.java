package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.time.*;
import java.util.*;

public interface NettedTransferReminderStorePort {
    record Candidate(long groupId, UUID nettingId, UUID transferId) {}

    List<Candidate> automaticCandidates(LocalDateTime now, int limit);

    OptionalLong reserve(UUID transferId, LocalDateTime now, Duration interval);

    Map<UUID, LocalDateTime> findLastRemindedAt(Collection<UUID> transferIds);
}
