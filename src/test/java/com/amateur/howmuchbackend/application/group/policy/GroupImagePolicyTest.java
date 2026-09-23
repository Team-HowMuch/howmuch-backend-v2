package com.amateur.howmuchbackend.application.group.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("그룹 이미지 정책")
class GroupImagePolicyTest {
    private final GroupImagePolicy policy = new GroupImagePolicy();

    @Test
    @DisplayName("파일명이 아닌 signature로 JPEG를 판별한다")
    void groupImage_jpegSignature_recognizedByBytes() {
        var image = policy.validate(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});

        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.extension()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("PNG와 WebP signature를 허용한다")
    void groupImage_pngOrWebpSignature_accepted() {
        assertThat(policy.validate(new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        }).extension()).isEqualTo("png");
        assertThat(policy.validate(new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
        }).extension()).isEqualTo("webp");
    }

    @Test
    @DisplayName("빈 파일과 지원하지 않는 signature를 거절한다")
    void rejectsInvalidImage() {
        assertInvalid(new byte[0]);
        assertInvalid(new byte[]{1, 2, 3, 4});
    }

    private void assertInvalid(byte[] content) {
        assertThatThrownBy(() -> policy.validate(content))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.INVALID_GROUP_IMAGE));
    }
}
