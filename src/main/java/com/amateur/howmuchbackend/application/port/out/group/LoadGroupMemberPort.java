package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.GroupMember;

import java.util.List;
import java.util.Optional;

public interface LoadGroupMemberPort {
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    Optional<GroupMember> findByGroupIdAndUserIdForUpdate(Long groupId, Long userId);

    List<GroupMember> findActiveByGroupId(Long groupId);

    int countActiveByGroupId(Long groupId);

    List<GroupSummaryReadModel> findActiveGroupSummariesByUserId(Long userId);
}
