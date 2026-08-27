package com.amateur.howmuchbackend.adapter.out.persistence.group.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "groups",
        indexes = {
                @Index(name = "idx_groups_owner", columnList = "owner_id"),
                @Index(name = "idx_groups_status", columnList = "status")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = Group.MAX_NAME_LENGTH)
    private String name;

    @Column(length = Group.MAX_MEMO_LENGTH)
    private String memo;

    @Column(name = "image_file_name", length = 100)
    private String imageFileName;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "invite_code", nullable = false, unique = true, length = Group.INVITE_CODE_LENGTH)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupStatus status;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public GroupJpaEntity(Group group) {
        this.ownerId = group.ownerId();
        this.inviteCode = group.inviteCode();
        this.creatorId = group.creatorId();
        updateFrom(group);
    }

    public void updateFrom(Group group) {
        this.name = group.name();
        this.memo = group.memo();
        this.imageFileName = group.imageFileName();
        this.ownerId = group.ownerId();
        this.status = group.status();
        this.deletedAt = group.deletedAt();
    }
}
