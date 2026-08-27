package com.amateur.howmuchbackend.adapter.in.web.user.dto.response;

import com.amateur.howmuchbackend.application.port.in.user.result.MyProfileResult;
import com.amateur.howmuchbackend.domain.user.Gender;
import com.amateur.howmuchbackend.domain.user.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MyProfileWebResponse(
        Long userId,
        String name,
        String publicId,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber,
        String avatarUrl,
        boolean phoneVerified,
        UserRole role,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
    private static final String AVATAR_URL = "/api/v2/user/me/avatar";

    public static MyProfileWebResponse from(MyProfileResult response) {
        return new MyProfileWebResponse(
                response.userId(),
                response.name(),
                response.publicId(),
                response.birthDate(),
                response.gender(),
                response.phoneNumber(),
                response.hasAvatar() ? AVATAR_URL : null,
                response.phoneVerified(),
                response.role(),
                response.lastLoginAt(),
                response.createdAt()
        );
    }
}
