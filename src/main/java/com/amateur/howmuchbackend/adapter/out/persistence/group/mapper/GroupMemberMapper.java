package com.amateur.howmuchbackend.adapter.out.persistence.group.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupMemberJpaEntity;
import com.amateur.howmuchbackend.domain.group.GroupMember;

public final class GroupMemberMapper {
    private GroupMemberMapper() {
    }

    public static GroupMemberJpaEntity toEntity(GroupMember member) {
        return new GroupMemberJpaEntity(member);
    }

    public static GroupMember toDomain(GroupMemberJpaEntity entity) {
        return new GroupMember(
                entity.getId(),
                entity.getGroupId(),
                entity.getUserId(),
                entity.getRole(),
                entity.getStatus(),
                entity.getJoinedAt(),
                entity.getLeftAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
