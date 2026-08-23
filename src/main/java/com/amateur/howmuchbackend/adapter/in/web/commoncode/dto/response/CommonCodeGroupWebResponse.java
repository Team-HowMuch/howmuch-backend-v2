package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response;

import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;

import java.util.List;

public record CommonCodeGroupWebResponse(
        String groupCode,
        String groupName,
        String description,
        String attr1Name,
        String attr2Name,
        String attr3Name,
        boolean active,
        long revision,
        List<CommonCodeDetailWebResponse> details
) {
    public static CommonCodeGroupWebResponse from(CommonCodeGroupResult result) {
        return new CommonCodeGroupWebResponse(
                result.groupCode(),
                result.groupName(),
                result.description(),
                result.attr1Name(),
                result.attr2Name(),
                result.attr3Name(),
                result.active(),
                result.revision(),
                result.details().stream().map(CommonCodeDetailWebResponse::from).toList()
        );
    }
}
