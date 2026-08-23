package com.amateur.howmuchbackend.domain.commoncode;

public record CommonCodeGroupSummary(
        String groupCode,
        String groupName,
        String description,
        boolean active,
        long revision,
        long detailCount
) {
}
