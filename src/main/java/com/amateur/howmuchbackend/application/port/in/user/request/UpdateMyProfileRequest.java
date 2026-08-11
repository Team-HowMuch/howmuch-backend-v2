package com.amateur.howmuchbackend.application.port.in.user.request;

import java.util.Arrays;

public record UpdateMyProfileRequest(
        String nickname,
        byte[] avatarContent,
        boolean resetAvatar
) {
    public UpdateMyProfileRequest {
        if (avatarContent != null) {
            avatarContent = Arrays.copyOf(avatarContent, avatarContent.length);
        }
    }

    @Override
    public byte[] avatarContent() {
        return avatarContent == null ? null : Arrays.copyOf(avatarContent, avatarContent.length);
    }
}
