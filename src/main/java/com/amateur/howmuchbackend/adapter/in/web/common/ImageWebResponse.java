package com.amateur.howmuchbackend.adapter.in.web.common;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

public final class ImageWebResponse {
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ofDays(1))
            .cachePrivate();

    private ImageWebResponse() {
    }

    public static ResponseEntity<byte[]> ok(String contentType, byte[] content) {
        return ResponseEntity.ok()
                .cacheControl(CACHE_CONTROL)
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }
}
