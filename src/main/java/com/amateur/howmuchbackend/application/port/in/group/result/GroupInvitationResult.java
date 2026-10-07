package com.amateur.howmuchbackend.application.port.in.group.result;

import com.amateur.howmuchbackend.domain.group.GroupInvitationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record GroupInvitationResult(UUID id, Long groupId, String groupName, String inviterName,
                                    GroupInvitationStatus status, LocalDateTime expiresAt) {}
