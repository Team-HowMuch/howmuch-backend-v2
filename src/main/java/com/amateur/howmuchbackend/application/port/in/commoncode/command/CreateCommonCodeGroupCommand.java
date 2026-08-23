package com.amateur.howmuchbackend.application.port.in.commoncode.command;

public record CreateCommonCodeGroupCommand(
        String groupCode,
        String groupName,
        String description,
        String attr1Name,
        String attr2Name,
        String attr3Name,
        boolean active
) {
}
