package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;

public interface CreateGroupUseCase {
    GroupDetailResult create(CreateGroupCommand command);
}
