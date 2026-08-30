package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;

import java.time.LocalDateTime;

public record GroupDetailWebResponse(
        Long groupId,
        String name,
        String memo,
        String imageUrl,
        int memberCount,
        String inviteCode,
        String inviteUrl,
        GroupMemberRole myRole,
        LocalDateTime createdAt
) {
    public static GroupDetailWebResponse from(GroupDetailResult result) {
        return new GroupDetailWebResponse(
                result.groupId(),
                result.name(),
                result.memo(),
                result.imageUrl(),
                result.memberCount(),
                result.inviteCode(),
                result.inviteUrl(),
                result.myRole(),
                result.createdAt()
        );
    }
}
