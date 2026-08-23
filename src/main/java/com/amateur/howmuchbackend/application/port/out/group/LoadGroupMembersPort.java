package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;

import java.util.Optional;

public interface LoadGroupMembersPort {
    Optional<GroupMemberRoster> findActiveRoster(Long groupId);
}
