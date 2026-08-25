package com.amateur.howmuchbackend.domain.group;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

public record Group(
        Long id,
        String name,
        String memo,
        String imageFileName,
        Long ownerId,
        String inviteCode,
        GroupStatus status,
        Long creatorId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
    public static final int MAX_NAME_LENGTH = 20;
    public static final int MAX_MEMO_LENGTH = 60;
    public static final int INVITE_CODE_LENGTH = 8;
    private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{8}$");

    public Group {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("그룹 ID는 양수여야 합니다.");
        }
        name = normalizeName(name);
        memo = normalizeMemo(memo);
        requirePositive(ownerId, "그룹 OWNER ID는 필수입니다.");
        requirePositive(creatorId, "그룹 생성자 ID는 필수입니다.");
        if (inviteCode == null || !INVITE_CODE_PATTERN.matcher(inviteCode).matches()) {
            throw new IllegalArgumentException("그룹 초대 코드는 8자리 대문자 영숫자여야 합니다.");
        }
        Objects.requireNonNull(status, "그룹 상태는 필수입니다.");
        Objects.requireNonNull(createdAt, "그룹 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "그룹 수정 시각은 필수입니다.");
        if ((status == GroupStatus.ACTIVE && deletedAt != null)
                || (status == GroupStatus.DELETED && deletedAt == null)) {
            throw new IllegalArgumentException("그룹 상태와 삭제 시각이 일치하지 않습니다.");
        }
    }

    public static Group create(
            String name,
            String memo,
            String imageFileName,
            Long creatorId,
            String inviteCode,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "그룹 생성 시각은 필수입니다.");
        return new Group(
                null,
                name,
                memo,
                imageFileName,
                creatorId,
                inviteCode,
                GroupStatus.ACTIVE,
                creatorId,
                now,
                now,
                null
        );
    }

    public Group changeSettings(
            String changedName,
            String changedMemo,
            String changedImageFileName,
            LocalDateTime now
    ) {
        requireActive();
        return new Group(
                id,
                changedName,
                changedMemo,
                changedImageFileName,
                ownerId,
                inviteCode,
                status,
                creatorId,
                createdAt,
                Objects.requireNonNull(now, "그룹 수정 시각은 필수입니다."),
                deletedAt
        );
    }

    public Group delete(LocalDateTime now) {
        requireActive();
        LocalDateTime deletedTime = Objects.requireNonNull(now, "그룹 삭제 시각은 필수입니다.");
        return new Group(
                id,
                name,
                memo,
                null,
                ownerId,
                inviteCode,
                GroupStatus.DELETED,
                creatorId,
                createdAt,
                deletedTime,
                deletedTime
        );
    }

    public void requireActive() {
        if (status != GroupStatus.ACTIVE) {
            throw ApplicationException.of(GroupStatusCode.GROUP_NOT_FOUND);
        }
    }

    public boolean hasImage() {
        return imageFileName != null;
    }

    private static String normalizeName(String value) {
        if (value == null) {
            throw ApplicationException.of(GroupStatusCode.INVALID_GROUP_NAME);
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw ApplicationException.of(GroupStatusCode.INVALID_GROUP_NAME);
        }
        return normalized;
    }

    private static String normalizeMemo(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_MEMO_LENGTH) {
            throw ApplicationException.of(GroupStatusCode.INVALID_GROUP_MEMO);
        }
        return normalized;
    }

    private static void requirePositive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }
}
