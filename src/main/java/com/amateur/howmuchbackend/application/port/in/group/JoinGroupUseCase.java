package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;

public interface JoinGroupUseCase {
    GroupDetailResult join(JoinGroupCommand command);
}
