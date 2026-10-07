package com.amateur.howmuchbackend.domain.group;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record GroupInvitation(UUID id, Long groupId, Long inviterUserId, Long recipientUserId,
                              GroupInvitationStatus status, LocalDateTime expiresAt,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    public GroupInvitation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (groupId == null || groupId <= 0 || inviterUserId == null || inviterUserId <= 0
                || recipientUserId == null || recipientUserId <= 0
                || inviterUserId.equals(recipientUserId) || !expiresAt.isAfter(createdAt))
            throw new IllegalArgumentException("그룹 초대 정보가 유효하지 않습니다.");
    }

    public static GroupInvitation pending(Long groupId, Long inviterId, Long recipientId, LocalDateTime now) {
        return new GroupInvitation(UUID.randomUUID(), groupId, inviterId, recipientId,
                GroupInvitationStatus.PENDING, now.plusDays(7), now, now);
    }

    public boolean isPendingAt(LocalDateTime now) {
        return status == GroupInvitationStatus.PENDING && now.isBefore(expiresAt);
    }

    public GroupInvitation accept(LocalDateTime now) { return decide(GroupInvitationStatus.ACCEPTED, now); }
    public GroupInvitation decline(LocalDateTime now) { return decide(GroupInvitationStatus.DECLINED, now); }
    public GroupInvitation cancel(LocalDateTime now) {
        if (status != GroupInvitationStatus.PENDING) throw new IllegalStateException("대기 초대만 취소할 수 있습니다.");
        return changed(GroupInvitationStatus.CANCELLED, now);
    }
    public GroupInvitation expire(LocalDateTime now) {
        if (status != GroupInvitationStatus.PENDING || now.isBefore(expiresAt))
            throw new IllegalStateException("만료된 대기 초대만 만료 처리할 수 있습니다.");
        return changed(GroupInvitationStatus.EXPIRED, now);
    }

    public GroupInvitationStatus effectiveStatus(LocalDateTime now) {
        return status == GroupInvitationStatus.PENDING && !isPendingAt(now)
                ? GroupInvitationStatus.EXPIRED : status;
    }

    private GroupInvitation decide(GroupInvitationStatus next, LocalDateTime now) {
        if (!isPendingAt(now)) throw new IllegalStateException("유효한 대기 초대만 결정할 수 있습니다.");
        return changed(next, now);
    }
    private GroupInvitation changed(GroupInvitationStatus next, LocalDateTime now) {
        return new GroupInvitation(id, groupId, inviterUserId, recipientUserId, next, expiresAt,
                createdAt, Objects.requireNonNull(now));
    }
}
