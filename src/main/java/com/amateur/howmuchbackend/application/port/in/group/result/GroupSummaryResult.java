package com.amateur.howmuchbackend.application.port.in.group.result;

import java.util.List;

public record GroupSummaryResult(
        Long groupId,
        String name,
        String imageUrl,
        int memberCount,
        List<String> memberNames
) {
}
