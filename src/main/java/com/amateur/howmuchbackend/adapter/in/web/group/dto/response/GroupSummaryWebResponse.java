package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupSummaryResult;

import java.util.List;

public record GroupSummaryWebResponse(
        Long groupId,
        String name,
        String imageUrl,
        int memberCount,
        List<String> memberNames
) {
    public static GroupSummaryWebResponse from(GroupSummaryResult result) {
        return new GroupSummaryWebResponse(result.groupId(), result.name(), result.imageUrl(),
                result.memberCount(), result.memberNames());
    }
}
