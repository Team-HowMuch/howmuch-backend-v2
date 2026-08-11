package com.amateur.howmuchbackend.application.user.model;

import java.util.Arrays;
import java.util.Objects;

public record ProfileImage(
        String contentType,
        byte[] content
) {
    public ProfileImage {
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(content);
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
