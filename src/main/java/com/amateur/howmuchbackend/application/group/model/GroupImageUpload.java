package com.amateur.howmuchbackend.application.group.model;

import java.util.Objects;

public record GroupImageUpload(
        String contentType,
        String extension,
        byte[] content
) {
    public GroupImageUpload {
        Objects.requireNonNull(contentType, "그룹 이미지 content type은 필수입니다.");
        Objects.requireNonNull(extension, "그룹 이미지 확장자는 필수입니다.");
        Objects.requireNonNull(content, "그룹 이미지 내용은 필수입니다.");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
