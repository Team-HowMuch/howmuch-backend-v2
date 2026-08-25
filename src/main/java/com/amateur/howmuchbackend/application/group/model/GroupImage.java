package com.amateur.howmuchbackend.application.group.model;

import java.util.Objects;

public record GroupImage(
        String contentType,
        byte[] content
) {
    public GroupImage {
        Objects.requireNonNull(contentType, "그룹 이미지 content type은 필수입니다.");
        Objects.requireNonNull(content, "그룹 이미지 내용은 필수입니다.");
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
