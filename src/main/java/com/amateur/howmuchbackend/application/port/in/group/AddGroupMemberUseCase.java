package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.command.AddGroupMemberCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupMemberResult;

public interface AddGroupMemberUseCase {
    GroupMemberResult add(Long groupId, AddGroupMemberCommand command);
}
