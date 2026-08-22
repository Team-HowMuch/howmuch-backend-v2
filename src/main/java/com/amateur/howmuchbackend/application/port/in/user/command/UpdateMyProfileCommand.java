package com.amateur.howmuchbackend.application.port.in.user.command;

import java.util.Arrays;

public record UpdateMyProfileCommand(
        String publicId,
        byte[] avatarContent,
        boolean resetAvatar
) {
    public UpdateMyProfileCommand {
        if (avatarContent != null) {
            avatarContent = Arrays.copyOf(avatarContent, avatarContent.length);
        }
    }

    @Override
    public byte[] avatarContent() {
        return avatarContent == null ? null : Arrays.copyOf(avatarContent, avatarContent.length);
    }
}
