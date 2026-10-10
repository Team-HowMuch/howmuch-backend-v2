package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupInvitationResult;
import com.amateur.howmuchbackend.domain.group.GroupInvitationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record GroupInvitationWebResponse(UUID id, Long groupId, String groupName, String inviterName,
                                         GroupInvitationStatus status, LocalDateTime expiresAt) {
    public static GroupInvitationWebResponse from(GroupInvitationResult result) {
        return new GroupInvitationWebResponse(result.id(), result.groupId(), result.groupName(),
                result.inviterName(), result.status(), result.expiresAt());
    }
}
