package com.amateur.howmuchbackend.domain.group;

import java.time.LocalDateTime;
import java.util.Objects;

public record GroupMember(
        Long id,
        Long groupId,
        Long userId,
        GroupMemberRole role,
        GroupMemberStatus status,
        LocalDateTime joinedAt,
        LocalDateTime leftAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public GroupMember {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("그룹멤버 ID는 양수여야 합니다.");
        }
        requirePositive(groupId, "그룹 ID는 필수입니다.");
        requirePositive(userId, "그룹멤버 사용자 ID는 필수입니다.");
        Objects.requireNonNull(role, "그룹멤버 역할은 필수입니다.");
        Objects.requireNonNull(status, "그룹멤버 상태는 필수입니다.");
        Objects.requireNonNull(joinedAt, "그룹 가입 시각은 필수입니다.");
        Objects.requireNonNull(createdAt, "그룹멤버 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "그룹멤버 수정 시각은 필수입니다.");
        if (role == GroupMemberRole.OWNER && status != GroupMemberStatus.ACTIVE) {
            throw new IllegalArgumentException("OWNER는 활성 상태여야 합니다.");
        }
        if ((status == GroupMemberStatus.ACTIVE && leftAt != null)
                || (status != GroupMemberStatus.ACTIVE && leftAt == null)) {
            throw new IllegalArgumentException("그룹멤버 상태와 종료 시각이 일치하지 않습니다.");
        }
    }

    public static GroupMember owner(Long groupId, Long userId, LocalDateTime now) {
        Objects.requireNonNull(now, "그룹멤버 생성 시각은 필수입니다.");
        return new GroupMember(
                null,
                groupId,
                userId,
                GroupMemberRole.OWNER,
                GroupMemberStatus.ACTIVE,
                now,
                null,
                now,
                now
        );
    }

    public static GroupMember member(Long groupId, Long userId, LocalDateTime now) {
        Objects.requireNonNull(now, "그룹멤버 생성 시각은 필수입니다.");
        return new GroupMember(
                null,
                groupId,
                userId,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.ACTIVE,
                now,
                null,
                now,
                now
        );
    }

    public GroupMember rejoin(LocalDateTime now) {
        if (status != GroupMemberStatus.LEFT || role != GroupMemberRole.MEMBER) {
            throw new IllegalStateException("LEFT MEMBER만 그룹에 재가입할 수 있습니다.");
        }
        LocalDateTime rejoinedAt = Objects.requireNonNull(now, "그룹 재가입 시각은 필수입니다.");
        return new GroupMember(
                id,
                groupId,
                userId,
                role,
                GroupMemberStatus.ACTIVE,
                rejoinedAt,
                null,
                createdAt,
                rejoinedAt
        );
    }

    public boolean isActive() {
        return status == GroupMemberStatus.ACTIVE;
    }

    public boolean isOwner() {
        return role == GroupMemberRole.OWNER;
    }

    private static void requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }
}
