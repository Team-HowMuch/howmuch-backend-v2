package com.amateur.howmuchbackend.application.port.in.commoncode.result;

public record CommonCodeGroupSummaryResult(
        String groupCode,
        String groupName,
        String description,
        boolean active,
        long revision,
        long detailCount
) {
}
