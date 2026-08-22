package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PublicIdPolicy {
    private static final String GENERATED_PREFIX = "user_";
    private static final int GENERATED_SUFFIX_LENGTH = 12;
    private static final char[] GENERATED_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final Pattern PUBLIC_ID_PATTERN = Pattern.compile("^[a-z0-9._]{4,20}$");
    private static final Pattern PUBLIC_ID_PREFIX_PATTERN = Pattern.compile("^[a-z0-9._]{1,20}$");

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder publicId = new StringBuilder(GENERATED_PREFIX);
        for (int index = 0; index < GENERATED_SUFFIX_LENGTH; index++) {
            publicId.append(GENERATED_CHARACTERS[secureRandom.nextInt(GENERATED_CHARACTERS.length)]);
        }
        return publicId.toString();
    }

    public String normalize(String publicId) {
        if (publicId == null) {
            throw invalidPublicId();
        }

        String normalized = publicId.strip().toLowerCase(Locale.ROOT);
        if (!PUBLIC_ID_PATTERN.matcher(normalized).matches()) {
            throw invalidPublicId();
        }
        return normalized;
    }

    public String normalizePrefix(String publicIdPrefix) {
        if (publicIdPrefix == null) {
            throw invalidPublicIdSearchPrefix();
        }

        String normalized = publicIdPrefix.strip().toLowerCase(Locale.ROOT);
        if (!PUBLIC_ID_PREFIX_PATTERN.matcher(normalized).matches()) {
            throw invalidPublicIdSearchPrefix();
        }
        return normalized;
    }

    private ApplicationException invalidPublicId() {
        return ApplicationException.of(UserStatusCode.INVALID_PUBLIC_ID);
    }

    private ApplicationException invalidPublicIdSearchPrefix() {
        return ApplicationException.of(UserStatusCode.INVALID_PUBLIC_ID_SEARCH_PREFIX);
    }
}
