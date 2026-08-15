package com.amateur.howmuchbackend.domain.user;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record User(
        Long id,
        String identityKey,
        String name,
        String nickname,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber,
        String pinHash,
        String fcmToken,
        String avatarFileName,
        boolean phoneVerified,
        UserRole role,
        boolean active,
        LocalDateTime lastLoginAt,
        LocalDateTime requiredTermsAgreedAt,
        LocalDateTime marketingAgreedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static User register(
            String identityKey,
            String name,
            String nickname,
            String phoneNumber,
            String pinHash,
            LocalDateTime now,
            boolean marketingAgreed
    ) {
        return new User(
                null,
                identityKey,
                name,
                nickname,
                null,
                null,
                phoneNumber,
                pinHash,
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                now,
                marketingAgreed ? now : null,
                now,
                now
        );
    }

    public User markLoggedIn(LocalDateTime now) {
        return new User(
                id,
                identityKey,
                name,
                nickname,
                birthDate,
                gender,
                phoneNumber,
                pinHash,
                fcmToken,
                avatarFileName,
                phoneVerified,
                role,
                active,
                now,
                requiredTermsAgreedAt,
                marketingAgreedAt,
                createdAt,
                now
        );
    }

    public User changeProfile(String nickname, String avatarFileName, LocalDateTime now) {
        return new User(
                id,
                identityKey,
                name,
                nickname,
                birthDate,
                gender,
                phoneNumber,
                pinHash,
                fcmToken,
                avatarFileName,
                phoneVerified,
                role,
                active,
                lastLoginAt,
                requiredTermsAgreedAt,
                marketingAgreedAt,
                createdAt,
                now
        );
    }
}
