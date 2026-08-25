package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupAccessService {
    private final LoadGroupPort loadGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;

    public Group loadActive(Long groupId) {
        Group group = loadGroupPort.findById(groupId)
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND));
        group.requireActive();
        return group;
    }

    public GroupMember requireActiveMember(Long groupId, Long userId) {
        GroupMember member = loadGroupMemberPort.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> ApplicationException.of(GroupStatusCode.GROUP_ACCESS_DENIED));
        if (!member.isActive()) {
            throw ApplicationException.of(GroupStatusCode.GROUP_ACCESS_DENIED);
        }
        return member;
    }
}
