package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.GroupMember;

public interface SaveGroupMemberPort {
    GroupMember save(GroupMember member);
}
