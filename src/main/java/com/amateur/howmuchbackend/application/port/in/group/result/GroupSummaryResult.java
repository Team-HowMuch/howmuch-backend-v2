package com.amateur.howmuchbackend.application.port.in.group.result;

public record GroupSummaryResult(
        Long groupId,
        String name,
        String imageUrl
) {
}
