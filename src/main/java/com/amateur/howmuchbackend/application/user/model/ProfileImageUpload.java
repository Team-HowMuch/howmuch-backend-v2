package com.amateur.howmuchbackend.application.user.model;

import java.util.Arrays;
import java.util.Objects;

public record ProfileImageUpload(
        String contentType,
        String extension,
        byte[] content
) {
    public ProfileImageUpload {
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(extension);
        Objects.requireNonNull(content);
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
