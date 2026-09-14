package com.amateur.howmuchbackend.domain.user;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record User(
        Long id,
        String identityKey,
        String name,
        String publicId,
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
    public static final int MAX_FCM_TARGET_LENGTH = 255;
    public static final String DELETED_USER_NAME = "탈퇴한 사용자";

    public static User register(
            String identityKey,
            String name,
            LocalDate birthDate,
            String publicId,
            String phoneNumber,
            String pinHash,
            LocalDateTime now,
            boolean marketingAgreed
    ) {
        return new User(
                null,
                identityKey,
                name,
                publicId,
                birthDate,
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
                publicId,
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

    public User changeProfile(String publicId, String avatarFileName, LocalDateTime now) {
        return new User(
                id,
                identityKey,
                name,
                publicId,
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

    public User deactivateForDeletion(
            String deletedIdentityKey,
            String deletedPublicId,
            String deletedPhoneNumber,
            String deletedPinHash,
            LocalDateTime now
    ) {
        if (!active) {
            throw new IllegalStateException("이미 비활성화된 사용자입니다.");
        }
        Objects.requireNonNull(deletedIdentityKey, "탈퇴 사용자 identity key는 필수입니다.");
        Objects.requireNonNull(deletedPublicId, "탈퇴 사용자 public id는 필수입니다.");
        Objects.requireNonNull(deletedPhoneNumber, "탈퇴 사용자 전화번호는 필수입니다.");
        Objects.requireNonNull(deletedPinHash, "탈퇴 사용자 PIN 해시는 필수입니다.");
        Objects.requireNonNull(now, "탈퇴 시각은 필수입니다.");
        return new User(
                id,
                deletedIdentityKey,
                DELETED_USER_NAME,
                deletedPublicId,
                null,
                null,
                deletedPhoneNumber,
                deletedPinHash,
                null,
                null,
                false,
                role,
                false,
                null,
                null,
                null,
                createdAt,
                now
        );
    }

}
