package com.amateur.howmuchbackend.application.port.out.group;

import java.util.List;

public record GroupSummaryReadModel(
        Long groupId,
        String name,
        String imageFileName,
        int memberCount,
        List<String> memberNames
) {
}
