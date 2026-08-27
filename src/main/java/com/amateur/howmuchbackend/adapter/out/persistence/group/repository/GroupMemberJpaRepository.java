package com.amateur.howmuchbackend.adapter.out.persistence.group.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupMemberJpaEntity;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupMemberJpaRepository extends JpaRepository<GroupMemberJpaEntity, Long> {
    Optional<GroupMemberJpaEntity> findByGroupIdAndUserId(Long groupId, Long userId);

    Optional<GroupMemberJpaEntity> findByGroupIdAndId(Long groupId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select member
              from GroupMemberJpaEntity member
             where member.groupId = :groupId
               and member.userId = :userId
            """)
    Optional<GroupMemberJpaEntity> findByGroupIdAndUserIdForUpdate(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId
    );

    List<GroupMemberJpaEntity> findAllByGroupIdAndStatus(
            Long groupId,
            GroupMemberStatus status
    );

    long countByGroupIdAndStatus(Long groupId, GroupMemberStatus status);

    @Query(value = """
            SELECT
                target_group.id AS groupId,
                member.user_id AS userId
              FROM groups target_group
              LEFT JOIN group_members member
                ON member.group_id = target_group.id
               AND member.status = 'ACTIVE'
             WHERE target_group.id = :groupId
               AND target_group.status = 'ACTIVE'
             ORDER BY
                CASE WHEN member.role = 'OWNER' THEN 0 ELSE 1 END,
                member.joined_at ASC,
                member.id ASC
            """, nativeQuery = true)
    List<GroupRosterProjection> findActiveRoster(@Param("groupId") Long groupId);

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

    interface GroupRosterProjection {
        Long getGroupId();

        Long getUserId();
    }
}
