package com.amateur.howmuchbackend.application.group.model;

import java.util.List;

public record GroupMemberRoster(
        Long groupId,
        List<Long> activeUserIds
) {
    public GroupMemberRoster {
        activeUserIds = List.copyOf(activeUserIds);
    }

    public boolean contains(Long userId) {
        return activeUserIds.contains(userId);
    }
}
