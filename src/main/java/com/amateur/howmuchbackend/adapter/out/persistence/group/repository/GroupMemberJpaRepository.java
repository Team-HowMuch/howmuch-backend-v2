package com.amateur.howmuchbackend.adapter.out.persistence.group.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupMemberJpaEntity;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMemberJpaEntity, Long> {
    Optional<GroupMemberJpaEntity> findByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupIdAndStatus(Long groupId, GroupMemberStatus status);

    @Query(value = """
            SELECT
                target_group.id AS groupId,
                target_group.name AS name,
                target_group.image_file_name AS imageFileName
              FROM group_members member
              JOIN groups target_group
                ON target_group.id = member.group_id
             WHERE member.user_id = :userId
               AND member.status = 'ACTIVE'
               AND target_group.status = 'ACTIVE'
             ORDER BY member.joined_at DESC, target_group.id DESC
            """, nativeQuery = true)
    List<GroupSummaryProjection> findActiveGroupSummariesByUserId(@Param("userId") Long userId);

    interface GroupSummaryProjection {
        Long getGroupId();

        String getName();

        String getImageFileName();
    }
}
