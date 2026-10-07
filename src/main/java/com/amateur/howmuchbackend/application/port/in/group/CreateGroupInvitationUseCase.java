package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupInvitationResult;
import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupInvitationCommand;

public interface CreateGroupInvitationUseCase {
    GroupInvitationResult create(Long groupId, CreateGroupInvitationCommand command);
}
