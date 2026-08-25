package com.amateur.howmuchbackend.application.port.out.group;

public record GroupSummaryReadModel(
        Long groupId,
        String name,
        String imageFileName
) {
}
