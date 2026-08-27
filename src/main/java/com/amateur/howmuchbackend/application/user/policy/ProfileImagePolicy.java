package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.application.user.model.ProfileImageUpload;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;

import java.util.Arrays;

public class ProfileImagePolicy {
    private static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024;

    public ProfileImageUpload validate(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_IMAGE_SIZE) {
            throw invalidImage();
        }

        if (isJpeg(content)) {
            return new ProfileImageUpload("image/jpeg", "jpg", content);
        }
        if (isPng(content)) {
            return new ProfileImageUpload("image/png", "png", content);
        }
        if (isWebp(content)) {
            return new ProfileImageUpload("image/webp", "webp", content);
        }
        throw invalidImage();
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] content) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        return content.length >= signature.length
                && Arrays.equals(Arrays.copyOf(content, signature.length), signature);
    }

    private boolean isWebp(byte[] content) {
        return content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P';
    }

    private ApplicationException invalidImage() {
        return ApplicationException.of(
                CommonStatusCode.INVALID_ARGUMENT,
                "아바타는 JPG, PNG, WebP 형식의 5 MiB 이하 이미지여야 합니다."
        );
    }
}
