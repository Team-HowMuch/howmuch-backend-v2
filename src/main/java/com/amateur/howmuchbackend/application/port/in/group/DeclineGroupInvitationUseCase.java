package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupInvitationResult;
import java.util.UUID;

public interface DeclineGroupInvitationUseCase {
    GroupInvitationResult decline(UUID invitationId);
}
