package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("그룹 영속 통합")
class GroupPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Autowired private GroupPersistenceAdapter groupPersistenceAdapter;
    @Autowired private GroupMemberPersistenceAdapter groupMemberPersistenceAdapter;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long groupId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key,
                            name,
                            public_id,
                            phone_number,
                            pin_hash,
                            phone_verified,
                            role,
                            active,
                            required_terms_agreed_at,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "group-persistence-" + uniqueKey,
                "최수아",
                "group_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
    }

    @AfterEach
    void tearDown() {
        if (groupId != null) {
            jdbcTemplate.update("DELETE FROM group_members WHERE group_id = ?", groupId);
            jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("그룹과 OWNER를 저장하고 내 그룹 목록·인원수를 조회한다")
    void savesAndLoadsGroupWithOwner() {
        Group savedGroup = saveGroup();
        GroupMember savedOwner = groupMemberPersistenceAdapter.save(
                GroupMember.owner(savedGroup.id(), userId, CREATED_AT)
        );

        var summaries = groupMemberPersistenceAdapter.findActiveGroupSummariesByUserId(userId);

        assertThat(savedGroup.id()).isPositive();
        assertThat(savedOwner.id()).isPositive();
        assertThat(groupPersistenceAdapter.findById(savedGroup.id())).contains(savedGroup);
        assertThat(groupMemberPersistenceAdapter.countActiveByGroupId(savedGroup.id())).isEqualTo(1);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().groupId()).isEqualTo(savedGroup.id());
        assertThat(summaries.getFirst().name()).isEqualTo("여행 모임");
    }

    @Test
    @DisplayName("그룹을 소프트 삭제해도 기존 OWNER 멤버십은 보존하고 목록에서는 숨긴다")
    void softDeletePreservesMembership() {
        Group savedGroup = saveGroup();
        groupMemberPersistenceAdapter.save(GroupMember.owner(savedGroup.id(), userId, CREATED_AT));

        Group deleted = groupPersistenceAdapter.update(savedGroup.delete(CREATED_AT.plusHours(1)));

        assertThat(deleted.status()).isEqualTo(GroupStatus.DELETED);
        assertThat(groupMemberPersistenceAdapter.findByGroupIdAndUserId(savedGroup.id(), userId)).isPresent();
        assertThat(groupMemberPersistenceAdapter.findActiveGroupSummariesByUserId(userId)).isEmpty();
        Integer membershipCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE group_id = ?",
                Integer.class,
                savedGroup.id()
        );
        assertThat(membershipCount).isEqualTo(1);
    }

    private Group saveGroup() {
        Group saved = groupPersistenceAdapter.save(Group.create(
                "여행 모임",
                "메모",
                null,
                userId,
                "AB" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(),
                CREATED_AT
        ));
        groupId = saved.id();
        return saved;
    }
}
