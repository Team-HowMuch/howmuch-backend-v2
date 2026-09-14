package com.amateur.howmuchbackend.application.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ImageCacheVersion {
    private ImageCacheVersion() {
    }

    public static String fromNullableFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fileName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }
}
