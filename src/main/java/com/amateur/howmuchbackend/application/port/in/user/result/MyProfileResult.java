package com.amateur.howmuchbackend.application.port.in.user.result;

import com.amateur.howmuchbackend.domain.user.Gender;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MyProfileResult(
        Long userId,
        String name,
        String publicId,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber,
        boolean hasAvatar,
        boolean phoneVerified,
        UserRole role,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
    public static MyProfileResult from(User user) {
        return new MyProfileResult(
                user.id(),
                user.name(),
                user.publicId(),
                user.birthDate(),
                user.gender(),
                user.phoneNumber(),
                user.avatarFileName() != null,
                user.phoneVerified(),
                user.role(),
                user.lastLoginAt(),
                user.createdAt()
        );
    }
}
