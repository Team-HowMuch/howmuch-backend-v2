package com.amateur.howmuchbackend.application.group.policy;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class GroupInviteCodePolicy {
    private static final char[] CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(CHARACTERS[secureRandom.nextInt(CHARACTERS.length)]);
        }
        return code.toString();
    }
}
