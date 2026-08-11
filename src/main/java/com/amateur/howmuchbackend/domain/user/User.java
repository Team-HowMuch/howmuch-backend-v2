package com.amateur.howmuchbackend.domain.user;

import com.amateur.howmuchbackend.domain.auth.identity.AuthIdentity;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static User register(AuthIdentity identity, String pinHash, LocalDateTime now) {
        return new User(
                null,
                identity.identityKey(),
                identity.name(),
                identity.name(),
                identity.birthDate(),
                identity.gender(),
                identity.phoneNumber(),
                pinHash,
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
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
                createdAt,
                now
        );
    }
}
