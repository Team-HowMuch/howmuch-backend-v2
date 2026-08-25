package com.amateur.howmuchbackend.application.port.in.group.command;

public record CreateGroupCommand(
        String name,
        String memo,
        byte[] imageContent
) {
    public CreateGroupCommand {
        imageContent = imageContent == null ? null : imageContent.clone();
    }

    @Override
    public byte[] imageContent() {
        return imageContent == null ? null : imageContent.clone();
    }
}
