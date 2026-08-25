package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupMemberResult;

import java.util.List;

public interface GetGroupMembersUseCase {
    List<GroupMemberResult> getMembers(Long groupId);
}
