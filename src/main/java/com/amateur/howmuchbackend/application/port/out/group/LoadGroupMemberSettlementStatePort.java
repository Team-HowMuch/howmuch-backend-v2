package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.util.Set;

public interface LoadGroupMemberSettlementStatePort {
    Set<SettlementStatus> findStatuses(Long groupId, Long userId);
}
