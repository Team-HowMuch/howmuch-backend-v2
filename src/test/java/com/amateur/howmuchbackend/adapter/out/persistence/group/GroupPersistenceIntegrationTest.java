package com.amateur.howmuchbackend.adapter.out.persistence.group;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementPersistenceAdapter;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.group.service.GroupOwnershipService;
import com.amateur.howmuchbackend.application.port.in.group.command.TransferGroupOwnershipCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.MICROS;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("그룹 영속 통합")
class GroupPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 25, 6, 0);

    @Autowired private GroupPersistenceAdapter groupPersistenceAdapter;
    @Autowired private GroupMemberPersistenceAdapter groupMemberPersistenceAdapter;
    @Autowired private GroupOwnershipPersistenceAdapter groupOwnershipPersistenceAdapter;
    @Autowired private SettlementPersistenceAdapter settlementPersistenceAdapter;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long userId;
    private Long groupId;
    private final List<Long> userIds = new ArrayList<>();
    private final List<UUID> settlementIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userId = insertUser("최수아");
    }

    @AfterEach
    void tearDown() {
        settlementIds.forEach(id -> jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", id));
        settlementIds.clear();
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
        assertThat(groupPersistenceAdapter.findById(savedGroup.id())).hasValueSatisfying(loaded -> {
            assertThat(loaded).usingRecursiveComparison()
                    .ignoringFields("createdAt", "updatedAt")
                    .isEqualTo(savedGroup);
            // PostgreSQL timestamps have microsecond precision; Linux clocks may return nanoseconds.
            assertThat(loaded.createdAt()).isCloseTo(savedGroup.createdAt(), within(1, MICROS));
            assertThat(loaded.updatedAt()).isCloseTo(savedGroup.updatedAt(), within(1, MICROS));
        });
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

    @Test
    @DisplayName("동시 소유권 이전 요청은 직렬화되어 활성 OWNER를 한 명만 유지한다")
    void serializesConcurrentOwnershipTransfers() throws Exception {
        Long targetUserId = insertUser("새 OWNER");
        Group savedGroup = saveGroup();
        groupMemberPersistenceAdapter.save(GroupMember.owner(
                savedGroup.id(), userId, CREATED_AT
        ));
        GroupMember target = groupMemberPersistenceAdapter.save(GroupMember.member(
                savedGroup.id(), targetUserId, CREATED_AT.plusMinutes(1)
        ));
        GroupOwnershipService ownershipService = new GroupOwnershipService(
                new CurrentActorPort() {
                    @Override
                    public CurrentActor getCurrentActor() {
                        return new CurrentActor(userId);
                    }

                    @Override
                    public Optional<CurrentActor> findCurrentActor() {
                        return Optional.of(getCurrentActor());
                    }
                },
                new GroupAccessService(groupPersistenceAdapter, groupMemberPersistenceAdapter),
                groupMemberPersistenceAdapter,
                groupOwnershipPersistenceAdapter
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                start.await();
                try {
                    transactionTemplate.executeWithoutResult(ignored -> ownershipService.transfer(
                            savedGroup.id(),
                            new TransferGroupOwnershipCommand(target.id())
                    ));
                    return true;
                } catch (ApplicationException exception) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        Group loadedGroup = groupPersistenceAdapter.findById(savedGroup.id()).orElseThrow();
        List<GroupMember> activeMembers = groupMemberPersistenceAdapter
                .findActiveByGroupId(savedGroup.id());
        assertThat(loadedGroup.ownerId()).isEqualTo(targetUserId);
        assertThat(activeMembers).filteredOn(GroupMember::isOwner)
                .singleElement()
                .extracting(GroupMember::userId)
                .isEqualTo(targetUserId);
        Integer ownerCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM group_members
                         WHERE group_id = ?
                           AND role = 'OWNER'
                           AND status = 'ACTIVE'
                        """,
                Integer.class,
                savedGroup.id()
        );
        assertThat(ownerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("멤버 탈퇴가 먼저 group row를 잠그면 정산 요청 재검증은 탈퇴 결과를 읽는다")
    void revalidatesGroupRosterAfterConcurrentMemberExit() throws Exception {
        Long memberUserId = insertUser("탈퇴 멤버");
        Group savedGroup = saveGroup();
        groupMemberPersistenceAdapter.save(GroupMember.owner(
                savedGroup.id(), userId, CREATED_AT
        ));
        GroupMember member = groupMemberPersistenceAdapter.save(GroupMember.member(
                savedGroup.id(), memberUserId, CREATED_AT.plusMinutes(1)
        ));
        UUID settlementId = UUID.randomUUID();
        settlementIds.add(settlementId);
        settlementPersistenceAdapter.save(new Settlement(
                settlementId,
                userId,
                "그룹멤버 동시성 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        ));

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch groupLocked = new CountDownLatch(1);
        CountDownLatch settlementLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> exit = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                groupPersistenceAdapter.findByIdForUpdate(savedGroup.id()).orElseThrow();
                groupLocked.countDown();
                await(settlementLocked);
                groupMemberPersistenceAdapter.update(member.leave(CREATED_AT.plusHours(1)));
            }));
            Future<Boolean> request = executor.submit(() -> {
                await(groupLocked);
                return transactionTemplate.execute(ignored -> {
                    settlementPersistenceAdapter.findByIdForUpdate(settlementId).orElseThrow();
                    settlementLocked.countDown();
                    return groupMemberPersistenceAdapter.lockAndLoad(savedGroup.id())
                            .orElseThrow()
                            .contains(memberUserId);
                });
            });

            exit.get();
            assertThat(request.get()).isFalse();
        } finally {
            executor.shutdownNow();
        }

        assertThat(groupMemberPersistenceAdapter.findByGroupIdAndUserId(
                savedGroup.id(), memberUserId
        )).get().extracting(GroupMember::status).isEqualTo(GroupMemberStatus.LEFT);
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

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기가 중단되었습니다.", exception);
        }
    }
}
