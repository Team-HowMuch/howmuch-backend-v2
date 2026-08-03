package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.domain.user.User;

public final class UserMapper {
    private UserMapper() {
    }

    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(user);
    }

    public static User toDomain(UserJpaEntity entity) {
        return new User(
                entity.getId(),
                entity.getIdentityKey(),
                entity.getName(),
                entity.getBirthDate(),
                entity.getGender(),
                entity.getPhoneNumber(),
                entity.getPinHash(),
                entity.getFcmToken(),
                entity.isPhoneVerified(),
                entity.getRole(),
                entity.isActive(),
                entity.getLastLoginAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
