package com.amateur.howmuchbackend.adapter.out.persistence.group.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "group_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_group_members_group_user",
                columnNames = {"group_id", "user_id"}
        ),
        indexes = {
                @Index(
                        name = "idx_group_members_user_status_joined_at",
                        columnList = "user_id, status, joined_at"
                ),
                @Index(
                        name = "idx_group_members_group_status_role_joined_at",
                        columnList = "group_id, status, role, joined_at"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMemberJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupMemberStatus status;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    public GroupMemberJpaEntity(GroupMember member) {
        this.groupId = member.groupId();
        this.userId = member.userId();
        updateFrom(member);
    }

    public void updateFrom(GroupMember member) {
        this.role = member.role();
        this.status = member.status();
        this.joinedAt = member.joinedAt();
        this.leftAt = member.leftAt();
    }
}
