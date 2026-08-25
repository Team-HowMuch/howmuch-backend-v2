package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userId = insertUser("최수아");
    }

    @AfterEach
    void tearDown() {
        if (groupId != null) {
            jdbcTemplate.update("DELETE FROM group_members WHERE group_id = ?", groupId);
            jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
        }
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        userIds.clear();
    }

    private Long insertUser(String name) {
        String uniqueKey = UUID.randomUUID().toString();
        Long insertedUserId = jdbcTemplate.queryForObject("""
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
                name,
                "group_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        userIds.add(insertedUserId);
        return insertedUserId;
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
        assertThat(groupMemberPersistenceAdapter.findActiveRoster(savedGroup.id())).isEmpty();
        Integer membershipCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE group_id = ?",
                Integer.class,
                savedGroup.id()
        );
        assertThat(membershipCount).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("초대 코드로 그룹을 조회하고 LEFT 멤버를 재가입 상태로 갱신한다")
    void findsByInviteCodeAndRejoinsLeftMember() {
        Group savedGroup = saveGroup();
        GroupMember leftMember = groupMemberPersistenceAdapter.save(new GroupMember(
                null,
                savedGroup.id(),
                userId,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.LEFT,
                CREATED_AT,
                CREATED_AT.plusHours(1),
                CREATED_AT,
                CREATED_AT.plusHours(1)
        ));

        GroupMember rejoined = groupMemberPersistenceAdapter.update(
                leftMember.rejoin(CREATED_AT.plusHours(2))
        );

        assertThat(groupPersistenceAdapter.findByInviteCodeForUpdate(savedGroup.inviteCode()))
                .contains(savedGroup);
        assertThat(rejoined.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(rejoined.joinedAt()).isEqualTo(CREATED_AT.plusHours(2));
        GroupMember loaded = groupMemberPersistenceAdapter.findByGroupIdAndUserId(
                        savedGroup.id(),
                        userId
                )
                .orElseThrow();
        assertThat(loaded.id()).isEqualTo(rejoined.id());
        assertThat(loaded.status()).isEqualTo(GroupMemberStatus.ACTIVE);
        assertThat(loaded.joinedAt()).isEqualTo(CREATED_AT.plusHours(2));
        assertThat(loaded.leftAt()).isNull();
    }

    @Test
    @DisplayName("활성 멤버를 OWNER 우선, 가입 시각과 멤버 ID 순으로 조회한다")
    void loadsOrderedActiveMembers() {
        Long earlierMemberUserId = insertUser("먼저 가입한 멤버");
        Long laterMemberUserId = insertUser("나중 가입한 멤버");
        Long removedMemberUserId = insertUser("제거된 멤버");
        Group savedGroup = saveGroup();

        groupMemberPersistenceAdapter.save(GroupMember.owner(
                savedGroup.id(), userId, CREATED_AT.plusHours(3)
        ));
        groupMemberPersistenceAdapter.save(GroupMember.member(
                savedGroup.id(), laterMemberUserId, CREATED_AT.plusHours(2)
        ));
        groupMemberPersistenceAdapter.save(GroupMember.member(
                savedGroup.id(), earlierMemberUserId, CREATED_AT.plusHours(1)
        ));
        groupMemberPersistenceAdapter.save(new GroupMember(
                null,
                savedGroup.id(),
                removedMemberUserId,
                GroupMemberRole.MEMBER,
                GroupMemberStatus.REMOVED,
                CREATED_AT,
                CREATED_AT.plusHours(4),
                CREATED_AT,
                CREATED_AT.plusHours(4)
        ));

        var members = groupMemberPersistenceAdapter.findActiveByGroupId(savedGroup.id());

        assertThat(members).extracting(GroupMember::userId)
                .containsExactly(userId, earlierMemberUserId, laterMemberUserId);
        var roster = groupMemberPersistenceAdapter.findActiveRoster(savedGroup.id()).orElseThrow();
        assertThat(roster.activeUserIds())
                .containsExactly(userId, earlierMemberUserId, laterMemberUserId);
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
