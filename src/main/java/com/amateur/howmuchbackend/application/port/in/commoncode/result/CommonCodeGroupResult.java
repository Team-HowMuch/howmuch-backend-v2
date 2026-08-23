package com.amateur.howmuchbackend.application.port.in.commoncode.result;

import java.util.List;

public record CommonCodeGroupResult(
        String groupCode,
        String groupName,
        String description,
        String attr1Name,
        String attr2Name,
        String attr3Name,
        boolean active,
        long revision,
        List<CommonCodeDetailResult> details
) {
}
