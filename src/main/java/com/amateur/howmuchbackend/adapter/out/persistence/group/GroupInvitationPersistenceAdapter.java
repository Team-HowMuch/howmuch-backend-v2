package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.application.port.out.group.GroupInvitationStorePort;
import com.amateur.howmuchbackend.domain.group.GroupInvitation;
import com.amateur.howmuchbackend.domain.group.GroupInvitationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GroupInvitationPersistenceAdapter implements GroupInvitationStorePort {
    private final JdbcClient jdbc;
    private static final RowMapper<GroupInvitation> MAPPER = (rs, row) -> new GroupInvitation(
            rs.getObject("id", UUID.class), rs.getLong("group_id"), rs.getLong("inviter_user_id"),
            rs.getLong("recipient_user_id"), GroupInvitationStatus.valueOf(rs.getString("status")),
            rs.getObject("expires_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));

    @Override public Optional<GroupInvitation> findById(UUID id) {
        return jdbc.sql("SELECT * FROM group_invitations WHERE id = :id").param("id", id).query(MAPPER).optional();
    }
    @Override public Optional<GroupInvitation> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM group_invitations WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }
    @Override public Optional<GroupInvitation> findPending(Long groupId, Long recipientUserId) {
        return jdbc.sql("""
                SELECT * FROM group_invitations
                WHERE group_id = :groupId AND recipient_user_id = :recipientId AND status = 'PENDING'
                """).param("groupId", groupId).param("recipientId", recipientUserId).query(MAPPER).optional();
    }
    @Override public GroupInvitation save(GroupInvitation invitation) {
        jdbc.sql("""
                INSERT INTO group_invitations(id, group_id, inviter_user_id, recipient_user_id, status,
                    expires_at, created_at, updated_at)
                VALUES (:id, :groupId, :inviterId, :recipientId, :status, :expiresAt, :createdAt, :updatedAt)
                """).param("id", invitation.id()).param("groupId", invitation.groupId())
                .param("inviterId", invitation.inviterUserId()).param("recipientId", invitation.recipientUserId())
                .param("status", invitation.status().name()).param("expiresAt", invitation.expiresAt())
                .param("createdAt", invitation.createdAt()).param("updatedAt", invitation.updatedAt()).update();
        return invitation;
    }
    @Override public void cancelPending(Long groupId, Long recipientUserId, LocalDateTime now) {
        jdbc.sql("""
                UPDATE group_invitations SET status = 'CANCELLED', updated_at = :now
                WHERE group_id = :groupId AND recipient_user_id = :recipientId AND status = 'PENDING'
                """).param("groupId", groupId).param("recipientId", recipientUserId).param("now", now).update();
    }
    @Override public GroupInvitation update(GroupInvitation invitation) {
        int updated = jdbc.sql("""
                UPDATE group_invitations SET status = :status, updated_at = :updatedAt
                WHERE id = :id AND status = 'PENDING'
                """).param("id", invitation.id()).param("status", invitation.status().name())
                .param("updatedAt", invitation.updatedAt()).update();
        if (updated != 1) throw new IllegalStateException("그룹 초대 상태가 동시에 변경되었습니다.");
        return invitation;
    }
}
