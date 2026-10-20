package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import java.util.List;

public interface LoadGroupSettlementListPort {
    List<GroupSettlementListReadModel> findAllInGroup(
            Long groupId, Long viewerId, SettlementListStatusFilter status);

    record GroupSettlementListReadModel(
            SettlementListReadModel summary,
            Long requesterId,
            String requesterName,
            String requesterPublicId,
            boolean viewerParticipant,
            String requesterCategoryCode
    ) {}
}
