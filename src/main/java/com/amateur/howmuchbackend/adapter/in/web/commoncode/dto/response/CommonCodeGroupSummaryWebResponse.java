package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response;

import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupSummaryResult;

public record CommonCodeGroupSummaryWebResponse(
        String groupCode,
        String groupName,
        String description,
        boolean active,
        long revision,
        long detailCount
) {
    public static CommonCodeGroupSummaryWebResponse from(CommonCodeGroupSummaryResult result) {
        return new CommonCodeGroupSummaryWebResponse(
                result.groupCode(),
                result.groupName(),
                result.description(),
                result.active(),
                result.revision(),
                result.detailCount()
        );
    }
}
