package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementLinkJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.application.settlement.service.SettlementListService;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.time.temporal.ChronoUnit.MICROS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 목록 영속 조회")
class SettlementListPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 13, 12, 0);

    @Autowired
    private SettlementPersistenceAdapter settlementPersistenceAdapter;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementParticipantJpaRepository settlementParticipantJpaRepository;

    @Autowired
    private SettlementLinkJpaRepository settlementLinkJpaRepository;

    @Autowired
    private SettlementShareJpaRepository settlementShareJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long payerId;
    private Long otherUserId;
    private Long connectionId;
    private Long accountId;
    private UUID receiveSettlementId;
    private LocalDateTime receiveCreatedAt;
    private UUID receiveCreatorParticipantId;
    private UUID receivePayerParticipantId;
    private UUID paySettlementId;
    private UUID payPayerParticipantId;

    @BeforeEach
    void setUp() {
        cleanupFixtures();
        String uniqueKey = UUID.randomUUID().toString().replace("-", "");
        creatorId = insertUser("list-creator-" + uniqueKey, "받는 사람");
        payerId = insertUser("list-payer-" + uniqueKey, "낼 사람");
        otherUserId = insertUser("list-other-" + uniqueKey, "다른 사람");
        connectionId = insertConnection(otherUserId, uniqueKey);
        accountId = insertAccount(otherUserId, connectionId, uniqueKey);

        receiveSettlementId = UUID.randomUUID();
        paySettlementId = UUID.randomUUID();
        SettlementJpaEntity receiveSettlement = new SettlementJpaEntity(new Settlement(
                receiveSettlementId,
                creatorId,
                "받을 정산",
                600L,
                SettlementStatus.AWAITING_DEPOSIT,
                CREATED_AT,
                CREATED_AT
        ));
        SettlementJpaEntity paySettlement = new SettlementJpaEntity(new Settlement(
                paySettlementId,
                otherUserId,
                "낼 정산",
                6_000L,
                SettlementAllocationType.DUTCH_PAY,
                accountId,
                SettlementStatus.AWAITING_DEPOSIT,
                CREATED_AT.plusMinutes(1),
                CREATED_AT.plusMinutes(1)
        ));
        settlementJpaRepository.saveAllAndFlush(java.util.List.of(
                receiveSettlement,
                paySettlement
        ));
        // Pin raw UTC fixture values independently of auditing and the test JVM timezone.
        jdbcTemplate.update("UPDATE settlements SET created_at = ?, updated_at = ? WHERE id = ?",
                CREATED_AT, CREATED_AT, receiveSettlementId);
        receiveCreatedAt = CREATED_AT;

        SettlementParticipant receiveCreator = SettlementParticipant.registeredUser(
                receiveSettlementId, creatorId, "받는 사람", 0, CREATED_AT
        );
        SettlementParticipant receivePayer = SettlementParticipant.registeredUser(
                receiveSettlementId, otherUserId, "다른 사람", 1, CREATED_AT
        );
        receiveCreatorParticipantId = receiveCreator.id();
        receivePayerParticipantId = receivePayer.id();
        SettlementParticipant payCreator = SettlementParticipant.registeredUser(
                paySettlementId, otherUserId, "다른 사람", 0, CREATED_AT
        );
        SettlementParticipant payPayer = SettlementParticipant.registeredUser(
                paySettlementId, payerId, "낼 사람", 1, CREATED_AT
        );
        payPayerParticipantId = payPayer.id();
        settlementParticipantJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementParticipantJpaEntity(receiveCreator),
                new SettlementParticipantJpaEntity(receivePayer),
                new SettlementParticipantJpaEntity(payCreator),
                new SettlementParticipantJpaEntity(payPayer)
        ));

        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                )
                VALUES (?, ?, '0020', 'test-snapshot', '다른 사람', ?)
                """, paySettlementId, accountId, CREATED_AT);

        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                UUID.randomUUID(),
                paySettlementId,
                payPayerParticipantId,
                6_000L,
                CREATED_AT
        ));
    }

    @AfterEach
    void tearDown() {
        cleanupFixtures();
    }

    @Test
    @DisplayName("RECEIVE는 생성자 정산의 총액과 참여자 수를 조회한다")
    void loadsReceiveSettlementSummary() {
        var result = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.totalAmount()).isEqualTo(600L);
            assertThat(item.participantCount()).isEqualTo(2);
            // Share가 아직 없어도 납부 대상은 생성자를 뺀 실제 인원이어야 한다.
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isZero();
            // JDBC rounds nanosecond audit timestamps to PostgreSQL microsecond precision.
            assertThat(item.requestedAt()).isCloseTo(receiveCreatedAt, within(1, MICROS));
            assertThat(item.amountToPay()).isNull();
            assertThat(item.groups()).isEmpty();
        });
    }

    @Test
    @DisplayName("작성하다 만 초안은 목록에 나오지 않는다")
    void hidesDraftSettlement() {
        UUID draftId = UUID.randomUUID();
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                draftId,
                creatorId,
                "작성 중 정산",
                1_000L,
                SettlementStatus.DRAFT,
                CREATED_AT.plusMinutes(2),
                CREATED_AT.plusMinutes(2)
        )));
        settlementParticipantJpaRepository.saveAndFlush(new SettlementParticipantJpaEntity(
                SettlementParticipant.registeredUser(draftId, creatorId, "받는 사람", 0, CREATED_AT)
        ));

        var inProgress = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);
        var all = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.ALL,
                null,
                null, null, null, 21);

        assertThat(inProgress).extracting(item -> item.settlementId())
                .containsExactly(receiveSettlementId);
        assertThat(all).extracting(item -> item.settlementId())
                .containsExactly(receiveSettlementId);
    }

    @Test
    @DisplayName("RECEIVE는 실제 완납 참여자 수와 링크 생성 시각을 조회한다")
    void loadsCompletedParticipantCountAndRequestedAt() {
        RequestFixture request = insertReceiveRequest(600L);
        insertDeposit(request.shareId(), 300L);

        var partiallyPaid = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);

        assertThat(partiallyPaid).singleElement().satisfies(item -> {
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isZero();
            assertThat(item.requestedAt()).isCloseTo(request.requestedAt(), within(1, MICROS));
        });

        insertDeposit(request.shareId(), 300L);

        var fullyPaid = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);

        assertThat(fullyPaid).singleElement().satisfies(item -> {
            // 분모와 분자가 같은 기준이라 전원 납부 시 100%가 된다.
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("RECEIVE 완료 인원에서 생성자 본인 Share를 제외한다")
    void excludesCreatorShareFromCompletedParticipantCount() {
        RequestFixture request = insertReceiveRequest(600L);
        UUID creatorShareId = UUID.randomUUID();
        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                creatorShareId,
                receiveSettlementId,
                receiveCreatorParticipantId,
                100L,
                CREATED_AT
        ));
        insertDeposit(creatorShareId, 100L);

        var creatorOnlyPaid = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);

        assertThat(creatorOnlyPaid).singleElement()
                .extracting(item -> item.completedParticipantCount())
                .isEqualTo(0);

        insertDeposit(request.shareId(), 600L);

        var payerPaid = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null, null, null, 21);

        assertThat(payerPaid).singleElement()
                .extracting(item -> item.completedParticipantCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PAY는 연결 참여자의 Share 금액과 요청자 이름을 조회한다")
    void loadsPaySettlementSummary() {
        var result = settlementPersistenceAdapter.findPage(
                payerId,
                SettlementListTab.PAY,
                SettlementListStatusFilter.AWAITING_DEPOSIT,
                null,
                null, null, null, 21);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(paySettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.amountToPay()).isEqualTo(6_000L);
            assertThat(item.requesterName()).isEqualTo("다른 사람");
            assertThat(item.totalAmount()).isNull();
            assertThat(item.participantCount()).isNull();
            assertThat(item.groups()).isEmpty();
        });
    }

    @Test
    @DisplayName("그룹 필터는 해당 그룹 출처 참여자가 있는 정산만 조회한다")
    void filtersByParticipantSourceGroup() {
        Long groupId = insertGroup("목록 필터 그룹", creatorId);
        Long unauthorizedGroupId = insertGroup("탈퇴한 그룹", otherUserId);
        insertGroupMember(groupId, creatorId, "ACTIVE");
        insertGroupMember(unauthorizedGroupId, creatorId, "LEFT");
        jdbcTemplate.update("""
                UPDATE settlement_participants
                   SET source = 'GROUP_MEMBER', source_group_id = ?
                 WHERE id = ?
                """, groupId, receivePayerParticipantId);
        jdbcTemplate.update("""
                UPDATE settlement_participants
                   SET source = 'GROUP_MEMBER', source_group_id = ?
                 WHERE id = ?
                """, unauthorizedGroupId, receiveCreatorParticipantId);

        var matching = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                groupId,
                null, null, null, 21);
        var notMatching = settlementPersistenceAdapter.findPage(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                unauthorizedGroupId,
                null, null, null, 21);

        assertThat(matching).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
            assertThat(item.groups()).containsExactly(new SettlementListReadModel.GroupSummary(
                    groupId,
                    "목록 필터 그룹",
                    false
            ));
        });
        assertThat(notMatching).isEmpty();
    }

    @Test
    void acceptedYearMonthExtremesCanBeQueried() {
        for (YearMonth month : java.util.List.of(YearMonth.of(1, 1), YearMonth.of(9999, 12))) {
            assertThat(settlementPersistenceAdapter.findPage(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                    null, month, null, null, 21)).isEmpty();
        }
    }

    @Test
    void filtersCompletedMonthAtKoreanMonthBoundariesForBothTabs() {
        YearMonth month = YearMonth.of(2026, 9);
        LocalDateTime start = LocalDateTime.of(2026, 8, 31, 15, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 30, 15, 0);
        for (UUID id : java.util.List.of(receiveSettlementId, paySettlementId)) {
            jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED', created_at = ?, updated_at = ? WHERE id = ?",
                    start.minusDays(10), start, id);
        }
        for (LocalDateTime completedAt : java.util.List.of(start.minusNanos(1000), start,
                end.minusNanos(1000), end)) {
            jdbcTemplate.update("UPDATE settlements SET updated_at = ? WHERE id IN (?, ?)",
                    completedAt, receiveSettlementId, paySettlementId);
            var receive = settlementPersistenceAdapter.findPage(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, month, null, null, 21);
            var pay = settlementPersistenceAdapter.findPage(
                    payerId, SettlementListTab.PAY, SettlementListStatusFilter.COMPLETED, null, month, null, null, 21);
            boolean included = !completedAt.isBefore(start) && completedAt.isBefore(end);
            if (included) {
                assertThat(receive).singleElement().satisfies(item -> {
                    assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
                    assertThat(item.completedAt()).isEqualTo(completedAt);
                    // Existing requestedAt projection is outside this change; completedAt must use UTC.
                });
                assertThat(pay).singleElement().satisfies(item -> {
                    assertThat(item.settlementId()).isEqualTo(paySettlementId);
                    assertThat(item.completedAt()).isEqualTo(completedAt);
                });
            } else {
                assertThat(receive).isEmpty();
                assertThat(pay).isEmpty();
            }
        }
        assertThat(settlementPersistenceAdapter.findPage(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null, null, null, 21))
                .singleElement().satisfies(item -> assertThat(item.completedAt()).isEqualTo(end));
    }

    @Test
    void monthFilterExcludesUnfinishedStatesAndPreservesStatusIntersection() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 10, 0, 0);
        for (SettlementStatus state : SettlementStatus.values()) {
            jdbcTemplate.update("UPDATE settlements SET status = ?, updated_at = ? WHERE id = ?",
                    state.name(), completedAt, receiveSettlementId);
            var filtered = settlementPersistenceAdapter.findPage(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL,
                    null, YearMonth.of(2026, 9), null, null, 21);
            assertThat(filtered).hasSize(state == SettlementStatus.COMPLETED ? 1 : 0);
            var unfiltered = settlementPersistenceAdapter.findPage(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null, null, null, 21);
            if (state != SettlementStatus.DRAFT) {
                assertThat(unfiltered).singleElement().satisfies(item ->
                        assertThat(item.completedAt()).isEqualTo(
                                state == SettlementStatus.COMPLETED ? completedAt : null));
            }
        }
        jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED' WHERE id = ?", receiveSettlementId);
        assertThat(settlementPersistenceAdapter.findPage(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS,
                null, YearMonth.of(2026, 9), null, null, 21)).isEmpty();
    }

    @Test
    void completedMonthFilterPreservesGroupAccess() {
        Long groupId = insertGroup("월별 정산 그룹", creatorId);
        insertGroupMember(groupId, creatorId, "ACTIVE");
        jdbcTemplate.update("UPDATE settlement_participants SET source = 'GROUP_MEMBER', source_group_id = ? WHERE id = ?",
                groupId, receivePayerParticipantId);
        jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED', updated_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 12, 31, 15, 0), receiveSettlementId);
        assertThat(settlementPersistenceAdapter.findPage(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2027, 1), null, null, 21)).hasSize(1);
        assertThat(settlementPersistenceAdapter.findPage(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2026, 12), null, null, 21)).isEmpty();
        jdbcTemplate.update("UPDATE group_members SET status = 'LEFT', left_at = ? WHERE group_id = ? AND user_id = ?",
                CREATED_AT, groupId, creatorId);
        assertThat(settlementPersistenceAdapter.findPage(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2027, 1), null, null, 21)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul"})
    void traversesTiedRequestTimesWithoutMissingOrDuplicatingRows(String timezone) {
        var previousTimezone = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(timezone));
            assertTiedRequestTimePages();
        } finally {
            java.util.TimeZone.setDefault(previousTimezone);
        }
    }

    private void assertTiedRequestTimePages() {
        jdbcTemplate.update("UPDATE settlements SET status = 'DRAFT' WHERE id = ?", receiveSettlementId);
        List<UUID> ids = List.of(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        LocalDateTime time = CREATED_AT.withNano(123456000);
        for (UUID id : ids) {
            jdbcTemplate.update("""
                    INSERT INTO settlements (id, creator_id, title, total_amount, status, created_at, updated_at)
                    VALUES (?, ?, 'pagination-tie', 600, 'AWAITING_DEPOSIT', ?, ?)
                    """, id, creatorId, time, time);
        }
        // A link timestamp must take precedence over the settlement creation timestamp.
        UUID linkedId = ids.get(1);
        jdbcTemplate.update("UPDATE settlements SET created_at = ? WHERE id = ?", time.minusDays(1), linkedId);
        jdbcTemplate.update("""
                INSERT INTO settlement_links (id, settlement_id, token_hash, status, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), linkedId, randomExternalKey(), time.plusDays(1), time, time);
        var service = listService(creatorId);
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        for (int expectedSize : List.of(2, 2, 1)) {
            var page = service.get(new GetSettlementsCommand(null, null, null, null, cursor, 2));
            assertThat(page.items()).hasSize(expectedSize).allSatisfy(item ->
                    assertThat(item.requestedAt()).isEqualTo(time));
            seen.addAll(page.items().stream().map(SettlementListItemResult::settlementId).toList());
            assertThat(page.hasNext()).isEqualTo(seen.size() < ids.size());
            cursor = page.nextCursor();
        }
        assertThat(seen).containsExactlyElementsOf(ids);
        assertThat(cursor).isNull();
        assertThat(settlementPersistenceAdapter.findPage(creatorId, SettlementListTab.RECEIVE,
                SettlementListStatusFilter.ALL, null, null, time, ids.getLast(), 3)).isEmpty();
        assertThat(listService(payerId).get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, null, null, null, null, 2)).items()).isEmpty();
    }

    @Test
    void limitsLargeHistoryAndAggregationsForBothTabsAndMeasuresPlan() throws Exception {
        insertLargeHistory(1200);
        var receive = listService(creatorId).get(new GetSettlementsCommand(null, null, null, null, null, 50));
        assertThat(receive.items()).hasSize(50).allSatisfy(item -> {
            assertThat(item.totalAmount()).isEqualTo(600L);
            assertThat(item.participantCount()).isEqualTo(2);
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isEqualTo(1);
        });
        assertThat(receive.hasNext()).isTrue();
        var pay = listService(payerId).get(new GetSettlementsCommand(
                SettlementListTab.PAY, null, null, null, null, 50));
        assertThat(pay.items()).hasSize(50).allSatisfy(item -> {
            assertThat(item.amountToPay()).isEqualTo(600L);
            assertThat(item.requesterName()).isEqualTo("받는 사람");
        });
        assertThat(pay.items()).extracting(SettlementListItemResult::settlementId)
                .containsExactlyElementsOf(receive.items().stream().map(SettlementListItemResult::settlementId).toList());
        var next = listService(creatorId).get(new GetSettlementsCommand(null, null, null, null, receive.nextCursor(), 50));
        assertThat(next.items()).hasSize(50);
        assertThat(next.items()).extracting(SettlementListItemResult::settlementId)
                .doesNotContainAnyElementsOf(receive.items().stream().map(SettlementListItemResult::settlementId).toList());

        String sql = java.util.Arrays.stream(SettlementJpaRepository.class.getMethods())
                .filter(method -> method.getName().equals("findPageForUser")).findFirst().orElseThrow()
                .getAnnotation(Query.class).value();
        var params = new MapSqlParameterSource().addValue("userId", creatorId).addValue("tab", "RECEIVE")
                .addValue("status", "IN_PROGRESS").addValue("groupId", null, java.sql.Types.BIGINT)
                .addValue("completedFrom", null).addValue("completedBefore", null)
                .addValue("cursorRequestedAt", null).addValue("cursorId", null).addValue("limit", 51);
        for (String table : List.of("settlements", "settlement_participants", "settlement_shares",
                "settlement_deposits", "account_transactions", "settlement_links")) {
            jdbcTemplate.execute("ANALYZE " + table);
        }
        String planText = new NamedParameterJdbcTemplate(jdbcTemplate).queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, params, String.class);
        Path report = Path.of("build/reports/settlement-pagination-explain.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, planText);
        JsonNode plan = new ObjectMapper().readTree(planText);
        List<JsonNode> nodes = new ArrayList<>();
        collectPlanNodes(plan.get(0).path("Plan"), nodes);
        assertThat(nodes).filteredOn(node -> node.has("Subplan Name")
                        && "CTE selected_settlements".equals(node.path("Subplan Name").stringValue()))
                .singleElement().satisfies(node -> assertThat(node.path("Actual Rows").asInt()).isEqualTo(51));
        // With one payer/share per settlement, each grouped result is bounded by selected IDs.
        assertThat(nodes).filteredOn(node -> node.path("Node Type").stringValue().contains("Aggregate"))
                .hasSizeGreaterThanOrEqualTo(3).allSatisfy(node ->
                        assertThat(node.path("Actual Rows").asInt()).isLessThanOrEqualTo(51));
    }

    @Test
    void rechecksMonthAndGroupAccessOnEveryPage() {
        insertLargeHistory(5);
        Long groupId = insertGroup("페이지 그룹", creatorId);
        insertGroupMember(groupId, creatorId, "ACTIVE");
        jdbcTemplate.update("""
                UPDATE settlement_participants SET source = 'GROUP_MEMBER', source_group_id = ?
                 WHERE linked_user_id = ? AND settlement_id IN (
                     SELECT id FROM settlements WHERE creator_id = ? AND title = 'pagination-bulk'
                 )
                """, groupId, payerId, creatorId);
        jdbcTemplate.update("""
                UPDATE settlements SET status = 'COMPLETED', updated_at = ?
                 WHERE creator_id = ? AND title = 'pagination-bulk'
                """, LocalDateTime.of(2026, 9, 1, 0, 0), creatorId);
        var service = listService(creatorId);
        var first = service.get(new GetSettlementsCommand(SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, groupId, YearMonth.of(2026, 9), null, 2));
        assertThat(first.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(item.groups()).singleElement().satisfies(group -> assertThat(group.groupId()).isEqualTo(groupId));
        });
        var second = service.get(new GetSettlementsCommand(SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, groupId, YearMonth.of(2026, 9), first.nextCursor(), 2));
        assertThat(second.items()).hasSize(2).doesNotContainAnyElementsOf(first.items());
        assertThat(service.get(new GetSettlementsCommand(SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, groupId, YearMonth.of(2026, 8), first.nextCursor(), 2)).items()).isEmpty();
        assertThat(listService(otherUserId).get(new GetSettlementsCommand(SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, groupId, YearMonth.of(2026, 9), first.nextCursor(), 2)).items()).isEmpty();
        jdbcTemplate.update("UPDATE group_members SET status = 'LEFT', left_at = ? WHERE group_id = ? AND user_id = ?",
                CREATED_AT, groupId, creatorId);
        assertThat(service.get(new GetSettlementsCommand(SettlementListTab.RECEIVE,
                SettlementListStatusFilter.COMPLETED, groupId, YearMonth.of(2026, 9), first.nextCursor(), 2)).items()).isEmpty();
    }

    private SettlementListService listService(Long userId) {
        CurrentActorPort actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(userId));
        return new SettlementListService(actor, settlementPersistenceAdapter);
    }

    private void collectPlanNodes(JsonNode node, List<JsonNode> nodes) {
        nodes.add(node);
        node.path("Plans").forEach(child -> collectPlanNodes(child, nodes));
    }

    private void insertLargeHistory(int count) {
        jdbcTemplate.update("""
                INSERT INTO settlements (id, creator_id, title, total_amount, status, created_at, updated_at)
                SELECT md5('pagination-' || ? || '-' || n)::uuid, ?, 'pagination-bulk', 600,
                       'AWAITING_DEPOSIT', timestamp '2029-01-01' + n * interval '1 microsecond', timestamp '2029-01-01'
                  FROM generate_series(1, ?) n
                """, creatorId, creatorId, count);
        jdbcTemplate.update("""
                INSERT INTO settlement_participants
                    (id, settlement_id, source, display_name, linked_user_id, display_order, created_at, updated_at)
                SELECT md5(s.id::text || '-' || person.n)::uuid, s.id, 'REGISTERED_USER', '대량 참여자',
                       CASE WHEN person.n = 0 THEN ? ELSE ? END, person.n, s.created_at, s.created_at
                  FROM settlements s CROSS JOIN generate_series(0, 1) person(n)
                 WHERE s.creator_id = ? AND s.title = 'pagination-bulk'
                """, creatorId, payerId, creatorId);
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots
                    (settlement_id, source_account_id, organization, account_number_ciphertext, account_holder, created_at)
                SELECT id, ?, '0020', 'test-snapshot', '받는 사람', created_at
                  FROM settlements WHERE creator_id = ? AND title = 'pagination-bulk'
                """, accountId, creatorId);
        jdbcTemplate.update("""
                INSERT INTO settlement_shares (id, settlement_id, payer_participant_id, amount, created_at)
                SELECT md5(s.id::text || '-share')::uuid, s.id, p.id, 600, s.created_at
                  FROM settlements s JOIN settlement_participants p ON p.settlement_id = s.id
                 WHERE s.creator_id = ? AND s.title = 'pagination-bulk' AND p.linked_user_id = ?
                """, creatorId, payerId);
        jdbcTemplate.update("""
                INSERT INTO account_transactions
                    (account_id, external_transaction_key, transaction_type, amount,
                     transacted_at, description, after_balance, created_at, updated_at)
                SELECT ?, replace(s.id::text, '-', '') || md5(s.id::text || n), 'INCOME', 300, s.created_at,
                       'pagination-bulk', 100000, s.created_at, s.created_at
                  FROM settlements s CROSS JOIN generate_series(1, 2) n
                 WHERE s.creator_id = ? AND s.title = 'pagination-bulk'
                """, accountId, creatorId);
        jdbcTemplate.update("""
                INSERT INTO settlement_deposits
                    (id, settlement_id, share_id, source_account_id, account_transaction_id, matched_at)
                SELECT md5(t.external_transaction_key)::uuid, s.id, share.id, ?, t.id, s.created_at
                  FROM settlements s JOIN settlement_shares share ON share.settlement_id = s.id
                  JOIN account_transactions t ON left(t.external_transaction_key, 32) = replace(s.id::text, '-', '')
                 WHERE s.creator_id = ? AND s.title = 'pagination-bulk' AND t.account_id = ?
                """, accountId, creatorId, accountId);
    }

    private Long insertUser(String identityKey, String name) {
        String rolePrefix = identityKey.contains("creator")
                ? "c"
                : identityKey.contains("payer") ? "p" : "o";
        String uniqueSuffix = identityKey.substring(identityKey.lastIndexOf('-') + 1);
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                identityKey,
                name,
                identityKey.replace("-", "_").substring(0, 20),
                "t" + rolePrefix + uniqueSuffix.substring(0, 13),
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("""
                DELETE FROM settlement_deposits
                 WHERE settlement_id IN (
                     SELECT id FROM settlements
                      WHERE creator_id IN (
                          SELECT id FROM users WHERE identity_key LIKE 'list-%'
                      )
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM account_transactions
                 WHERE account_id IN (
                     SELECT id FROM account_records
                      WHERE user_id IN (
                          SELECT id FROM users WHERE identity_key LIKE 'list-%'
                      )
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM settlements
                 WHERE creator_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM group_members
                 WHERE group_id IN (
                     SELECT id FROM groups
                      WHERE creator_id IN (
                          SELECT id FROM users WHERE identity_key LIKE 'list-%'
                      )
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM groups
                 WHERE creator_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM accounts
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM account_records
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM external_connections
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("DELETE FROM users WHERE identity_key LIKE 'list-%'");
    }

    private RequestFixture insertReceiveRequest(long shareAmount) {
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                ) VALUES (?, ?, '0020', 'test-snapshot', '받는 사람', ?)
                """, receiveSettlementId, accountId, CREATED_AT);
        UUID shareId = UUID.randomUUID();
        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                shareId,
                receiveSettlementId,
                receivePayerParticipantId,
                shareAmount,
                CREATED_AT
        ));
        SettlementLinkJpaEntity link = settlementLinkJpaRepository.saveAndFlush(
                new SettlementLinkJpaEntity(
                        UUID.randomUUID(),
                        receiveSettlementId,
                        randomExternalKey(),
                        SettlementLinkStatus.ACTIVE,
                        LocalDateTime.of(2030, 1, 1, 0, 0)
                )
        );
        LocalDateTime requestedAt = CREATED_AT.plusHours(2);
        jdbcTemplate.update("UPDATE settlement_links SET created_at = ? WHERE id = ?", requestedAt, link.getId());
        return new RequestFixture(shareId, requestedAt);
    }

    private void insertDeposit(UUID shareId, long amount) {
        Long transactionId = jdbcTemplate.queryForObject("""
                        INSERT INTO account_transactions (
                            account_id, external_transaction_key, transaction_type, amount,
                            transacted_at, description, after_balance, created_at, updated_at
                        ) VALUES (?, ?, 'INCOME', ?, ?, '정산 입금', 100000, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                accountId,
                randomExternalKey(),
                amount,
                CREATED_AT.plusHours(3),
                CREATED_AT,
                CREATED_AT
        );
        jdbcTemplate.update("""
                INSERT INTO settlement_deposits (
                    id, settlement_id, share_id, source_account_id,
                    account_transaction_id, matched_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                receiveSettlementId,
                shareId,
                accountId,
                transactionId,
                CREATED_AT.plusHours(3)
        );
    }

    private String randomExternalKey() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private Long insertGroup(String name, Long ownerId) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO groups (
                            name, owner_id, invite_code, status, creator_id,
                            created_at, updated_at
                        )
                        VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                name,
                ownerId,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(),
                ownerId,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void insertGroupMember(Long groupId, Long userId, String status) {
        jdbcTemplate.update("""
                INSERT INTO group_members (
                    group_id, user_id, role, status, joined_at, left_at, created_at, updated_at
                ) VALUES (?, ?, 'MEMBER', ?, ?, ?, ?, ?)
                """,
                groupId,
                userId,
                status,
                CREATED_AT,
                "ACTIVE".equals(status) ? null : CREATED_AT.plusMinutes(1),
                CREATED_AT,
                CREATED_AT
        );
    }

    private record RequestFixture(UUID shareId, LocalDateTime requestedAt) {
    }

    private Long insertConnection(Long userId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint,
                            active, created_at, updated_at
                        )
                        VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                "list-connection-" + uniqueKey,
                "d".repeat(64),
                CREATED_AT,
                CREATED_AT
        );
    }

    private Long insertAccount(Long userId, Long externalConnectionId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number,
                            balance, currency, active, product_code, is_default,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '정산 계좌',
                                '****0000', 1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                externalConnectionId,
                "list-account-" + uniqueKey,
                "list-account-ciphertext-" + uniqueKey,
                CREATED_AT,
                CREATED_AT
        );
    }
}
