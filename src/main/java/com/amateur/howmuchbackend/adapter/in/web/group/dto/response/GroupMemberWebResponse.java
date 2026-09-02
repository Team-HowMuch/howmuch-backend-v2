package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupMemberResult;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;

import java.time.LocalDateTime;

public record GroupMemberWebResponse(
        Long memberId,
        String publicId,
        String name,
        GroupMemberRole role,
        LocalDateTime joinedAt,
        String avatarUrl
) {
    public static GroupMemberWebResponse from(Long groupId, GroupMemberResult result) {
        return new GroupMemberWebResponse(
                result.memberId(),
                result.publicId(),
                result.name(),
                result.role(),
                result.joinedAt(),
                result.hasAvatar()
                        ? "/api/v2/groups/%s/members/%s/avatar".formatted(
                                groupId,
                                result.memberId()
                        )
                        : null
        );
    }
}
