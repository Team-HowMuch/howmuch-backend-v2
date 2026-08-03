package com.amateur.howmuchbackend.domain.user;

import com.amateur.howmuchbackend.domain.auth.identity.AuthIdentity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record User(
        Long id,
        String identityKey,
        String name,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber,
        String pinHash,
        String fcmToken,
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
                identity.birthDate(),
                identity.gender(),
                identity.phoneNumber(),
                pinHash,
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
                birthDate,
                gender,
                phoneNumber,
                pinHash,
                fcmToken,
                phoneVerified,
                role,
                active,
                now,
                createdAt,
                now
        );
    }
}
