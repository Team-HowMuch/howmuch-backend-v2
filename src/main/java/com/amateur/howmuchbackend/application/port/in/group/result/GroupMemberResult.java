package com.amateur.howmuchbackend.application.port.in.group.result;

import com.amateur.howmuchbackend.domain.group.GroupMemberRole;

import java.time.LocalDateTime;

public record GroupMemberResult(
        Long memberId,
        String publicId,
        String name,
        GroupMemberRole role,
        LocalDateTime joinedAt,
        boolean hasAvatar
) {
}
