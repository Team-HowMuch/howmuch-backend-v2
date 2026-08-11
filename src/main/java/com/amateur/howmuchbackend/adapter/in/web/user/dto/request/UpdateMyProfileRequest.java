package com.amateur.howmuchbackend.adapter.in.web.user.dto.request;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public record UpdateMyProfileRequest(
        String nickname,
        MultipartFile avatar,
        Boolean resetAvatar
) {
    public com.amateur.howmuchbackend.application.port.in.user.request.UpdateMyProfileRequest toRequest() {
        return new com.amateur.howmuchbackend.application.port.in.user.request.UpdateMyProfileRequest(
                nickname,
                avatarContent(),
                Boolean.TRUE.equals(resetAvatar)
        );
    }

    private byte[] avatarContent() {
        if (avatar == null || avatar.isEmpty()) {
            return null;
        }
        try {
            return avatar.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read profile image upload.", exception);
        }
    }
}
