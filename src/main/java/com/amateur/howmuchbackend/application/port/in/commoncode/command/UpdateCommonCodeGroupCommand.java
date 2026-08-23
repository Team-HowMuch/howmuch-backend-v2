package com.amateur.howmuchbackend.application.port.in.commoncode.command;

import java.util.List;

public record UpdateCommonCodeGroupCommand(
        long revision,
        String groupName,
        String description,
        String attr1Name,
        String attr2Name,
        String attr3Name,
        boolean active,
        List<CommonCodeDetailCommand> details
) {
}
