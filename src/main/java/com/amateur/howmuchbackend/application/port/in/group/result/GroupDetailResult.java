package com.amateur.howmuchbackend.application.port.in.group.result;

import com.amateur.howmuchbackend.domain.group.GroupMemberRole;

public record GroupDetailResult(
        Long groupId,
        String name,
        String memo,
        String imageUrl,
        int memberCount,
        String inviteCode,
        String inviteUrl,
        GroupMemberRole myRole
) {
}
