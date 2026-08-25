package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;

public interface TransferGroupOwnershipPort {
    void transfer(Group group, GroupMember previousOwner, GroupMember newOwner);
}
