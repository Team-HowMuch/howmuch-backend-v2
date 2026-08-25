package com.amateur.howmuchbackend.application.port.in.group.command;

public record UpdateGroupCommand(
        String name,
        String memo,
        boolean memoProvided,
        byte[] imageContent,
        boolean resetImage
) {
    public UpdateGroupCommand {
        imageContent = imageContent == null ? null : imageContent.clone();
    }

    @Override
    public byte[] imageContent() {
        return imageContent == null ? null : imageContent.clone();
    }
}
