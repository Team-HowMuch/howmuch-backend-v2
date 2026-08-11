package com.amateur.howmuchbackend.application.port.in.user.response;

import com.amateur.howmuchbackend.domain.user.Gender;
import com.amateur.howmuchbackend.domain.user.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MyProfileResponse(
        Long userId,
        String name,
        String nickname,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber,
        boolean hasAvatar,
        boolean phoneVerified,
        UserRole role,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
}
