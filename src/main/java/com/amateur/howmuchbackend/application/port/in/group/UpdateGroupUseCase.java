package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.command.UpdateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;

public interface UpdateGroupUseCase {
    GroupDetailResult update(Long groupId, UpdateGroupCommand command);
}
