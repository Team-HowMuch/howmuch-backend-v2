package com.amateur.howmuchbackend.application.group.policy;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;

@Component
public class GroupInviteCodePolicy {
    private static final char[] CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{8}$");
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(CHARACTERS[secureRandom.nextInt(CHARACTERS.length)]);
        }
        return code.toString();
    }

    public String normalize(String inviteCode) {
        if (inviteCode == null) {
            throw invalidInviteCode();
        }
        String normalized = inviteCode.strip().toUpperCase(Locale.ROOT);
        if (!INVITE_CODE_PATTERN.matcher(normalized).matches()) {
            throw invalidInviteCode();
        }
        return normalized;
    }

    private ApplicationException invalidInviteCode() {
        return ApplicationException.of(GroupStatusCode.INVALID_GROUP_INVITE_CODE);
    }
}
