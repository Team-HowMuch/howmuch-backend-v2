package com.amateur.howmuchbackend.application.port.in.commoncode.command;

public record CommonCodeDetailCommand(
        String code,
        String name,
        String description,
        int sortOrder,
        String attr1,
        String attr2,
        String attr3,
        boolean active
) {
}
