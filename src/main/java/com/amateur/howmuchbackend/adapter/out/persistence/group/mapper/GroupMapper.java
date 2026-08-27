package com.amateur.howmuchbackend.adapter.out.persistence.group.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupJpaEntity;
import com.amateur.howmuchbackend.domain.group.Group;

public final class GroupMapper {
    private GroupMapper() {
    }

    public static GroupJpaEntity toEntity(Group group) {
        return new GroupJpaEntity(group);
    }

    public static Group toDomain(GroupJpaEntity entity) {
        return new Group(
                entity.getId(),
                entity.getName(),
                entity.getMemo(),
                entity.getImageFileName(),
                entity.getOwnerId(),
                entity.getInviteCode(),
                entity.getStatus(),
                entity.getCreatorId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
