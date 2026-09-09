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
                target_group.image_file_name AS imageFileName,
                summary.memberCount AS memberCount,
                summary.firstMemberName AS firstMemberName,
                summary.secondMemberName AS secondMemberName,
                summary.thirdMemberName AS thirdMemberName
              FROM group_members member
              JOIN groups target_group
                ON target_group.id = member.group_id
              CROSS JOIN LATERAL (
                SELECT COUNT(*) AS memberCount,
                       MAX(ranked.name) FILTER (WHERE ranked.userId <> :userId AND ranked.position = 1) AS firstMemberName,
                       MAX(ranked.name) FILTER (WHERE ranked.userId <> :userId AND ranked.position = 2) AS secondMemberName,
                       MAX(ranked.name) FILTER (WHERE ranked.userId <> :userId AND ranked.position = 3) AS thirdMemberName
                  FROM (
                    SELECT member_user.name, active_member.user_id AS userId,
                           ROW_NUMBER() OVER (
                               PARTITION BY (active_member.user_id = :userId)
                               ORDER BY CASE WHEN active_member.role = 'OWNER' THEN 0 ELSE 1 END,
                                        active_member.joined_at ASC, active_member.id ASC
                           ) AS position
                      FROM group_members active_member
                      JOIN users member_user ON member_user.id = active_member.user_id
                     WHERE active_member.group_id = target_group.id
                       AND active_member.status = 'ACTIVE'
                       AND member_user.active = TRUE
                  ) ranked
              ) summary
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

        long getMemberCount();

        String getFirstMemberName();

        String getSecondMemberName();

        String getThirdMemberName();
    }

    interface GroupRosterProjection {
        Long getGroupId();

        Long getUserId();
    }
}
