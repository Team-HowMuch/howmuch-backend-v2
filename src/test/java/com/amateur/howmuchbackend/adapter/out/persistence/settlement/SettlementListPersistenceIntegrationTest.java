package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementLinkJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.application.settlement.service.SettlementListService;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementDepositTestCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;
import static java.time.temporal.ChronoUnit.MICROS;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 목록 영속 조회")
class SettlementListPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 13, 12, 0);

    @Autowired
    private SettlementPersistenceAdapter settlementPersistenceAdapter;

    @Autowired
    private SettlementListService settlementListService;

    @MockitoBean
    private CurrentActorPort currentActorPort;

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

    @Autowired
    private RequestedItemReceivableFactsPersistenceAdapter requestedItemReceivableFactsAdapter;

    private Long creatorId;
    private Long payerId;
    private Long otherUserId;
    private Long connectionId;
    private Long accountId;
    private Long creatorConnectionId;
    private Long receiveAccountId;
    private UUID receiveSettlementId;
    private LocalDateTime receiveCreatedAt;
    private UUID receiveCreatorParticipantId;
    private UUID receivePayerParticipantId;
    private UUID paySettlementId;
    private UUID payPayerParticipantId;
    private final List<UUID> fixtureSettlementIds = new ArrayList<>();
    private final List<Long> fixtureGroupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtureSettlementIds.clear();
        fixtureGroupIds.clear();
        String uniqueKey = UUID.randomUUID().toString().replace("-", "");
        creatorId = insertUser("list-creator-" + uniqueKey, "받는 사람");
        payerId = insertUser("list-payer-" + uniqueKey, "낼 사람");
        otherUserId = insertUser("list-other-" + uniqueKey, "다른 사람");
        connectionId = insertConnection(otherUserId, uniqueKey);
        accountId = insertAccount(otherUserId, connectionId, uniqueKey);
        creatorConnectionId = insertConnection(creatorId, uniqueKey + "-receive");
        receiveAccountId = insertAccount(creatorId, creatorConnectionId, uniqueKey + "-receive");

        receiveSettlementId = UUID.randomUUID();
        paySettlementId = UUID.randomUUID();
        fixtureSettlementIds.add(receiveSettlementId);
        fixtureSettlementIds.add(paySettlementId);
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
        receiveCreatedAt = receiveSettlement.getCreatedAt();

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
    void receiveSettlement_creatorAmountAndMemberCount_loaded() {
        var result = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.totalAmount()).isEqualTo(600L);
            assertThat(item.participantCount()).isEqualTo(2);
            // Share가 아직 없어도 납부 대상은 생성자를 뺀 실제 인원이어야 한다.
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isZero();
            // PostgreSQL은 감사 시각을 마이크로초 단위로 저장하므로 나노초 차이는 허용한다.
            assertThat(item.requestedAt()).isCloseTo(receiveCreatedAt, within(1, MICROS));
            assertThat(item.amountToPay()).isNull();
            assertThat(item.groups()).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "RECEIPT_OCR, 1, 롯데리아",
            "PAYMENT_HISTORY, 1, null",
            "RECEIPT_OCR, 2, null",
            "MANUAL, 1, null"
    }, nullValues = "null")
    @DisplayName("storeName은 표시 대상 지출이 단일 영수증일 때만 반환한다")
    void settlementList_storeName_singleReceiptOcrOnly(
            String source, int count, String expectedStoreName
    ) {
        for (int i = 0; i < count; i++) {
            String description = expectedStoreName != null ? expectedStoreName : "지출" + i;
            jdbcTemplate.update("""
                            INSERT INTO settlement_expenses (
                                id, settlement_id, source, source_reference, description, amount, display_order,
                                created_at, updated_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), receiveSettlementId, source,
                    "MANUAL".equals(source) ? null : UUID.randomUUID().toString(), description, 100L, i,
                    CREATED_AT, CREATED_AT);
        }

        var result = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.storeName()).isEqualTo(expectedStoreName)
        );
    }

    @Test
    @DisplayName("storeName은 영수증 1건과 결제내역 1건이 함께 있으면 null이다")
    void settlementList_storeName_receiptAndPaymentHistory_returnsNull() {
        jdbcTemplate.update("""
                        INSERT INTO settlement_expenses (
                            id, settlement_id, source, source_reference, description, amount, display_order,
                            created_at, updated_at
                        )
                        VALUES (?, ?, 'RECEIPT_OCR', ?, '롯데리아', 100, 0, ?, ?)
                        """,
                UUID.randomUUID(), receiveSettlementId, UUID.randomUUID().toString(), CREATED_AT, CREATED_AT);
        jdbcTemplate.update("""
                        INSERT INTO settlement_expenses (
                            id, settlement_id, source, source_reference, description, amount, display_order,
                            created_at, updated_at
                        )
                        VALUES (?, ?, 'PAYMENT_HISTORY', ?, '이체', 100, 1, ?, ?)
                        """,
                UUID.randomUUID(), receiveSettlementId, UUID.randomUUID().toString(), CREATED_AT, CREATED_AT);

        var result = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.storeName()).isNull()
        );
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SettlementStatus.class,
            names = {"AWAITING_DEPOSIT", "COMPLETED"})
    @DisplayName("확정 더치페이는 0원 참여 기록을 보존하고 PAY 목록과 총무·납부 인원에서 제외한다")
    void dutchPay_zeroAmount_hiddenFromPayAndExcludedFromCounts(SettlementStatus status) {
        var zeroParticipant = SettlementParticipant.registeredUser(
                paySettlementId, creatorId, "0원 참여자", 2, CREATED_AT);
        settlementParticipantJpaRepository.saveAndFlush(new SettlementParticipantJpaEntity(zeroParticipant));
        jdbcTemplate.update("UPDATE settlements SET status = ? WHERE id = ?", status.name(), paySettlementId);

        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null)).isEmpty();
        assertThat(settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> assertThat(item.amountToPay()).isEqualTo(6_000));
        assertThat(settlementPersistenceAdapter.findAll(
                otherUserId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> {
                    assertThat(item.participantCount()).isEqualTo(3);
                    assertThat(item.payableParticipantCount()).isEqualTo(1);
                });
        assertThat(settlementJpaRepository.findAllForTreasurer(creatorId, "PAY")).isEmpty();
        assertThat(settlementJpaRepository.findAllForTreasurer(otherUserId, "RECEIVE"))
                .singleElement().satisfies(item -> assertThat(item.getPayerCount()).isEqualTo(1));
    }

    @ParameterizedTest
    @CsvSource({"DUTCH_PAY,SUSPENDED", "ITEM_ALLOCATION,REQUESTED"})
    @DisplayName("중단 편집과 품목 선택 중 Share 부재는 PAY 목록과 실제 참여 인원을 유지한다")
    void unconfirmedWithoutShare_keepsPayEntryAndParticipantCount(String type, String status) {
        jdbcTemplate.update("DELETE FROM settlement_shares WHERE settlement_id = ?", paySettlementId);
        jdbcTemplate.update("UPDATE settlements SET allocation_type = ?, status = ? WHERE id = ?",
                type, status, paySettlementId);
        assertThat(settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> {
                    assertThat(item.amountToPay()).isNull();
                });
        assertThat(settlementPersistenceAdapter.findAll(
                otherUserId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> assertThat(item.payableParticipantCount()).isEqualTo(1));
        assertThat(settlementJpaRepository.findAllForTreasurer(payerId, "PAY")).hasSize(1);
    }

    @Test
    @DisplayName("삭제된 정산은 생성자와 참여자의 목록에서 모두 제외한다")
    void settlementList_softDeleted_hiddenFromBothTabs() {
        jdbcTemplate.update("UPDATE settlements SET deleted_at = ? WHERE id IN (?, ?)",
                CREATED_AT.plusDays(1), receiveSettlementId, paySettlementId);

        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null)).isEmpty();
        assertThat(settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlements WHERE id IN (?, ?)", Long.class,
                receiveSettlementId, paySettlementId)).isEqualTo(2L);
    }

    @Test
    @Transactional
    @DisplayName("완료 정산 논리 삭제 후에도 Share·입금·송금 매칭 이력과 완료 시각을 보존한다")
    void completedSettlement_softDelete_preservesFinancialMatches() {
        RequestFixture request = insertReceiveRequest(600L);
        insertDeposit(request.shareId(), 600L);
        Long paymentTransactionId = jdbcTemplate.queryForObject("""
                        INSERT INTO account_transactions (
                            account_id, external_transaction_key, transaction_type, amount,
                            transacted_at, created_at, updated_at
                        ) VALUES (?, ?, 'EXPENSE', 600, ?, ?, ?)
                        RETURNING id
                        """, Long.class, accountId, randomExternalKey(),
                CREATED_AT.plusHours(3), CREATED_AT, CREATED_AT);
        jdbcTemplate.update("""
                INSERT INTO settlement_payments (
                    id, settlement_id, share_id, payer_account_id, account_transaction_id, matched_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), receiveSettlementId, request.shareId(), accountId,
                paymentTransactionId, CREATED_AT.plusHours(3));
        jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED' WHERE id = ?", receiveSettlementId);
        LocalDateTime completedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM settlements WHERE id = ?", LocalDateTime.class, receiveSettlementId);

        settlementPersistenceAdapter.delete(receiveSettlementId, CREATED_AT.plusDays(1));

        assertThat(settlementPersistenceAdapter.findById(receiveSettlementId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id = ?", String.class, receiveSettlementId))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM settlements WHERE id = ?", LocalDateTime.class, receiveSettlementId))
                .isEqualTo(completedAt);
        for (String table : java.util.List.of("settlement_shares", "settlement_deposits", "settlement_payments")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM %s WHERE settlement_id = ?".formatted(table),
                    Long.class, receiveSettlementId)).as(table).isOne();
        }
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null))
                .extracting(SettlementListReadModel::settlementId)
                .doesNotContain(receiveSettlementId);
    }

    @Test
    @DisplayName("작성하다 만 초안은 목록에 나오지 않는다")
    void settlementList_draft_excluded() {
        UUID draftId = UUID.randomUUID();
        fixtureSettlementIds.add(draftId);
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

        var inProgress = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );
        var all = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.ALL,
                null,
                null
        );

        assertThat(inProgress).extracting(item -> item.settlementId())
                .containsExactly(receiveSettlementId);
        assertThat(all).extracting(item -> item.settlementId())
                .containsExactly(receiveSettlementId);
    }

    @Test
    @DisplayName("RECEIVE는 실제 완납 참여자 수와 링크 생성 시각을 조회한다")
    void receiveSettlement_completedMembersAndLinkTime_loaded() {
        RequestFixture request = insertReceiveRequest(600L);
        insertDeposit(request.shareId(), 300L);

        var partiallyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(partiallyPaid).singleElement().satisfies(item -> {
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isZero();
            assertThat(item.requestedAt()).isCloseTo(request.requestedAt(), within(1, MICROS));
        });

        insertDeposit(request.shareId(), 300L);

        var fullyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(fullyPaid).singleElement().satisfies(item -> {
            // 모든 참여자를 동일한 분모와 분자로 계산해 전원 납부 시 진행률을 100%로 반환한다.
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("RECEIVE 완료 인원에서 생성자 본인 Share를 제외한다")
    void receiveSettlement_completedCount_excludesCreatorShare() {
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

        var creatorOnlyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(creatorOnlyPaid).singleElement()
                .extracting(item -> item.completedParticipantCount())
                .isEqualTo(0);

        insertDeposit(request.shareId(), 600L);

        var payerPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null,
                null
        );

        assertThat(payerPaid).singleElement()
                .extracting(item -> item.completedParticipantCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PAY는 연결 참여자의 Share 금액과 요청자 이름을 조회한다")
    void paySettlement_linkedParticipantShareAndRequester_loaded() {
        var result = settlementPersistenceAdapter.findAll(
                payerId,
                SettlementListTab.PAY,
                SettlementListStatusFilter.AWAITING_DEPOSIT,
                null,
                null
        );

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(paySettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.amountToPay()).isEqualTo(6_000L);
            assertThat(item.requesterName()).isEqualTo("다른 사람");
            assertThat(item.totalAmount()).isEqualTo(6_000L);
            assertThat(item.participantCount()).isNull();
            assertThat(item.groups()).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "ITEM_ALLOCATION, REQUESTED, 6000",
            "ITEM_ALLOCATION, SUSPENDED, 6000",
            "ITEM_ALLOCATION, AWAITING_DEPOSIT, null",
            "ITEM_ALLOCATION, COMPLETED, null",
            "DUTCH_PAY, REQUESTED, null",
            "DUTCH_PAY, SUSPENDED, null"
    }, nullValues = "null")
    @DisplayName("본인 Share가 없는 품목 정산은 선택 대기·중단 상태에서만 총액을 표시한다")
    void paySettlement_missingShare_totalFallbackOnlyWhileItemAmountUnconfirmed(
            SettlementAllocationType allocationType,
            SettlementStatus status,
            Long expectedAmount
    ) {
        jdbcTemplate.update("DELETE FROM settlement_shares WHERE settlement_id = ?", paySettlementId);
        jdbcTemplate.update("UPDATE settlements SET allocation_type = ?, status = ? WHERE id = ?",
                allocationType.name(), status.name(), paySettlementId);
        // 다른 참여자의 Share가 있어도 본인 Share 부재를 기준으로 보정한다.
        UUID creatorParticipantId = jdbcTemplate.queryForObject(
                "SELECT id FROM settlement_participants WHERE settlement_id = ? AND linked_user_id = ?",
                UUID.class, paySettlementId, otherUserId);
        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                UUID.randomUUID(), paySettlementId, creatorParticipantId, 1_000L, CREATED_AT));

        var result = settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(paySettlementId);
            assertThat(item.amountToPay()).isNull();
            assertThat(item.totalAmount()).isEqualTo(6_000L);
            assertThat(item.requesterName()).isEqualTo("다른 사람");
        });
        assertThat(settlementPersistenceAdapter.findAll(
                otherUserId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> {
                    assertThat(item.totalAmount()).isEqualTo(6_000L);
                    assertThat(item.amountToPay()).isEqualTo(1_000L);
                    assertThat(item.participantCount()).isEqualTo(2);
                });
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(payerId));
        assertThat(settlementListService.get(new GetSettlementsCommand(
                SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null)))
                .singleElement().satisfies(item -> {
                    assertThat(item.amountToPay()).isEqualTo(expectedAmount);
                    assertThat(item.totalAmount()).isNull();
                });
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(otherUserId));
        assertThat(settlementListService.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null)))
                .singleElement().satisfies(item -> {
                    assertThat(item.amountToPay()).isNull();
                    assertThat(item.totalAmount()).isEqualTo(6_000L);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "REQUESTED, 2000", "SUSPENDED, 2000",
            "AWAITING_DEPOSIT, 2000", "COMPLETED, 2000"
    })
    @DisplayName("품목 정산의 확정 개인 금액은 총액과 달라도 유지한다")
    void paySettlement_existingItemShare_preservesPersonalAmount(SettlementStatus status, long amount) {
        jdbcTemplate.update("UPDATE settlements SET allocation_type = 'ITEM_ALLOCATION', status = ? WHERE id = ?",
                status.name(), paySettlementId);
        jdbcTemplate.update("UPDATE settlement_shares SET amount = ? WHERE payer_participant_id = ?",
                amount, payPayerParticipantId);

        assertThat(settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> assertThat(item.amountToPay()).isEqualTo(amount));
    }

    @Test
    @DisplayName("그룹 필터는 해당 그룹 출처 참여자가 있는 정산만 조회한다")
    void settlementList_sourceGroupFilter_returnsMatchingParticipants() {
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

        var matching = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                groupId,
                null
        );
        var notMatching = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                unauthorizedGroupId,
                null
        );

        assertThat(matching).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
            assertThat(item.groups()).containsExactly(new SettlementListReadModel.GroupSummary(
                    groupId,
                    "목록 필터 그룹",
                    null
            ));
        });
        assertThat(notMatching).isEmpty();
    }

    @Test
    void settlementList_yearMonthExtremes_queryable() {
        for (YearMonth month : java.util.List.of(YearMonth.of(1, 1), YearMonth.of(9999, 12))) {
            assertThat(settlementPersistenceAdapter.findAll(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                    null, month)).isEmpty();
        }
    }

    @Test
    void settlementList_koreanMonthBoundaries_filtersBothTabs() {
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
            var receive = settlementPersistenceAdapter.findAll(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, month);
            var pay = settlementPersistenceAdapter.findAll(
                    payerId, SettlementListTab.PAY, SettlementListStatusFilter.COMPLETED, null, month);
            boolean included = !completedAt.isBefore(start) && completedAt.isBefore(end);
            if (included) {
                assertThat(receive).singleElement().satisfies(item -> {
                    assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
                    assertThat(item.completedAt()).isEqualTo(completedAt);
                    // 기존 requestedAt projection은 유지하고 completedAt을 UTC 기준으로 검증한다.
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
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null))
                .singleElement().satisfies(item -> assertThat(item.completedAt()).isEqualTo(end));
    }

    @Test
    void settlementList_monthFilter_excludesUnfinishedPreservesStatus() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 10, 0, 0);
        for (SettlementStatus state : SettlementStatus.values()) {
            // V53 requires immutable compression sources for this state; the real Batch flow
            // and its list/detail projections are covered by GroupNettingDirectIntegrationTest.
            if (state == SettlementStatus.NETTING_IN_PROGRESS) continue;
            jdbcTemplate.update("UPDATE settlements SET status = ?, updated_at = ? WHERE id = ?",
                    state.name(), completedAt, receiveSettlementId);
            var filtered = settlementPersistenceAdapter.findAll(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL,
                    null, YearMonth.of(2026, 9));
            assertThat(filtered).hasSize(state == SettlementStatus.COMPLETED ? 1 : 0);
            var unfiltered = settlementPersistenceAdapter.findAll(
                    creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.ALL, null, null);
            if (state != SettlementStatus.DRAFT) {
                assertThat(unfiltered).singleElement().satisfies(item ->
                        assertThat(item.completedAt()).isEqualTo(
                                state == SettlementStatus.COMPLETED ? completedAt : null));
                assertThat(unfiltered).singleElement().satisfies(item ->
                        assertThat(item.endedAt()).isEqualTo(
                                state == SettlementStatus.COMPLETED || state == SettlementStatus.SUSPENDED
                                        ? completedAt
                                        : null));
            }
        }
        jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED' WHERE id = ?", receiveSettlementId);
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS,
                null, YearMonth.of(2026, 9))).isEmpty();
    }

    @Test
    void settlementList_completedMonth_preservesGroupAccess() {
        Long groupId = insertGroup("월별 정산 그룹", creatorId);
        insertGroupMember(groupId, creatorId, "ACTIVE");
        jdbcTemplate.update("UPDATE settlement_participants SET source = 'GROUP_MEMBER', source_group_id = ? WHERE id = ?",
                groupId, receivePayerParticipantId);
        jdbcTemplate.update("UPDATE settlements SET status = 'COMPLETED', updated_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 12, 31, 15, 0), receiveSettlementId);
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2027, 1))).hasSize(1);
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2026, 12))).isEmpty();
        jdbcTemplate.update("UPDATE group_members SET status = 'LEFT', left_at = ? WHERE group_id = ? AND user_id = ?",
                CREATED_AT, groupId, creatorId);
        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                groupId, YearMonth.of(2027, 1))).isEmpty();
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

    @Test
    @DisplayName("RECEIVE는 생성자 본인 Share를 뺀 Share 합계를 함께 조회하고 PAY는 비운다")
    void receiveSettlement_receivableShareAmount_excludesCreatorShare() {
        insertReceiveRequest(600L);
        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                UUID.randomUUID(),
                receiveSettlementId,
                receiveCreatorParticipantId,
                100L,
                CREATED_AT
        ));

        assertThat(settlementPersistenceAdapter.findAll(
                creatorId, SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null))
                .singleElement()
                .extracting(SettlementListReadModel::receivableShareAmount)
                .isEqualTo(600L);
        assertThat(settlementPersistenceAdapter.findAll(
                payerId, SettlementListTab.PAY, SettlementListStatusFilter.IN_PROGRESS, null, null))
                .singleElement()
                .extracting(SettlementListReadModel::receivableShareAmount)
                .isNull();
    }

    @Test
    @DisplayName("선택 대기 품목 정산의 목록 받을 금액은 영수증 총액에서 생성자가 고른 몫을 뺀다")
    void receiveSettlement_requestedItemAllocation_receivableAmountExcludesCreatorPick() {
        givenRequestedReceiptCreatorPickedCola();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));

        var result = settlementListService.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.totalAmount()).isEqualTo(12_000L);
            assertThat(item.receivableAmount()).isEqualTo(10_000L);
            assertThat(item.amountToPay()).isNull();
        });
    }

    @Test
    @DisplayName("받을 금액 계산 재료를 정산 ID 목록으로 한 번에 불러오고 없는 정산은 뺀다")
    void requestedItemReceivableFacts_loadedForSettlementIds() {
        givenRequestedReceiptCreatorPickedCola();
        UUID missingId = UUID.randomUUID();

        var facts = requestedItemReceivableFactsAdapter.findAllBySettlementIds(java.util.List.of(
                receiveSettlementId, missingId, paySettlementId, receiveSettlementId));

        assertThat(facts).extracting(fact -> fact.settlementId())
                .containsExactly(receiveSettlementId, paySettlementId);
        assertThat(facts.get(0)).satisfies(receipt -> {
            assertThat(receipt.totalAmount()).isEqualTo(12_000L);
            assertThat(receipt.creatorParticipantId()).isEqualTo(receiveCreatorParticipantId);
            assertThat(receipt.items()).extracting(item -> item.name()).containsExactly("피자", "콜라");
            assertThat(receipt.selections()).singleElement()
                    .satisfies(selection -> assertThat(selection.participantId()).isEqualTo(receiveCreatorParticipantId));
            assertThat(receipt.expenses()).singleElement()
                    .satisfies(expense -> assertThat(expense.amount()).isEqualTo(12_000L));
        });
        assertThat(facts.get(1)).satisfies(dutchPay -> {
            assertThat(dutchPay.creatorParticipantId()).isNotNull();
            assertThat(dutchPay.items()).isEmpty();
            assertThat(dutchPay.selections()).isEmpty();
        });
    }

    @Test
    @DisplayName("삭제된 정산의 계산 재료는 불러오지 않는다")
    void requestedItemReceivableFacts_deletedSettlement_excluded() {
        givenRequestedReceiptCreatorPickedCola();
        jdbcTemplate.update("UPDATE settlements SET deleted_at = ? WHERE id = ?",
                CREATED_AT.plusDays(1), receiveSettlementId);

        assertThat(requestedItemReceivableFactsAdapter.findAllBySettlementIds(java.util.List.of(receiveSettlementId)))
                .isEmpty();
        assertThat(requestedItemReceivableFactsAdapter.findAllBySettlementIds(java.util.List.of())).isEmpty();
    }

    @Test
    @DisplayName("저장된 행이 도메인 규칙과 맞지 않는 정산은 계산 재료에서 빼고 목록은 Share 합계로 대체한다")
    void requestedItemReceivable_brokenRow_skippedWithoutFailingList() {
        givenRequestedReceiptCreatorPickedCola();
        // 영수증이 아닌 지출에는 할인이 있을 수 없다. DB는 막지 않지만 도메인 변환에서 거부된다.
        jdbcTemplate.update("""
                INSERT INTO settlement_expenses (
                    id, settlement_id, source, description, amount, discount,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, 'MANUAL', '깨진 지출', 1000, 100, 1, ?, ?)
                """, UUID.randomUUID(), receiveSettlementId, CREATED_AT, CREATED_AT);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));

        assertThat(requestedItemReceivableFactsAdapter.findAllBySettlementIds(
                java.util.List.of(receiveSettlementId, paySettlementId)))
                .extracting(fact -> fact.settlementId())
                .containsExactly(paySettlementId);
        assertThat(settlementListService.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS, null, null)))
                .singleElement()
                .satisfies(item -> assertThat(item.receivableAmount()).isZero());
    }

    /** 총액 12,000원 영수증(피자 10,000원, 콜라 2,000원)에서 생성자가 콜라를 골랐고 참여자는 아직 고르지 않았다. */
    private void givenRequestedReceiptCreatorPickedCola() {
        jdbcTemplate.update("""
                UPDATE settlements
                   SET status = 'REQUESTED', allocation_type = 'ITEM_ALLOCATION', total_amount = 12000
                 WHERE id = ?
                """, receiveSettlementId);
        UUID expenseId = UUID.randomUUID();
        UUID pizzaId = UUID.randomUUID();
        UUID colaId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO settlement_expenses (
                    id, settlement_id, source, description, amount,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, 'MANUAL', '영수증', 12000, 0, ?, ?)
                """, expenseId, receiveSettlementId, CREATED_AT, CREATED_AT);
        jdbcTemplate.update("""
                INSERT INTO settlement_items (
                    id, settlement_id, expense_id, name, quantity, amount,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, ?, '피자', 1, 10000, 0, ?, ?), (?, ?, ?, '콜라', 1, 2000, 1, ?, ?)
                """, pizzaId, receiveSettlementId, expenseId, CREATED_AT, CREATED_AT,
                colaId, receiveSettlementId, expenseId, CREATED_AT, CREATED_AT);
        jdbcTemplate.update("""
                INSERT INTO settlement_item_selections (
                    id, settlement_id, item_id, participant_id, fraction,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ONE', ?, ?)
                """, UUID.randomUUID(), receiveSettlementId, colaId, receiveCreatorParticipantId,
                CREATED_AT, CREATED_AT);
    }

    private void cleanupFixtures() {
        if (fixtureSettlementIds.stream().anyMatch(id ->
                SettlementDepositTestCleanup.preserveSettlement(jdbcTemplate, id))) {
            return;
        }
        fixtureSettlementIds.forEach(id -> SettlementDepositTestCleanup.deleteDepositsWhenMutable(jdbcTemplate, id));
        fixtureSettlementIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM settlement_payments WHERE settlement_id = ?", id));
        fixtureSettlementIds.forEach(id -> jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", id));
        fixtureGroupIds.forEach(id -> jdbcTemplate.update("DELETE FROM group_members WHERE group_id = ?", id));
        fixtureGroupIds.forEach(id -> jdbcTemplate.update("DELETE FROM groups WHERE id = ?", id));
        if (accountId != null) {
            jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", accountId);
        }
        if (receiveAccountId != null) {
            jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", receiveAccountId);
        }
        if (creatorId != null && payerId != null && otherUserId != null) {
            jdbcTemplate.update("DELETE FROM accounts WHERE user_id IN (?, ?, ?)", creatorId, payerId, otherUserId);
            jdbcTemplate.update("DELETE FROM account_records WHERE user_id IN (?, ?, ?)", creatorId, payerId, otherUserId);
            jdbcTemplate.update("DELETE FROM external_connections WHERE user_id IN (?, ?, ?)", creatorId, payerId, otherUserId);
            jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", creatorId, payerId, otherUserId);
        }
    }

    private RequestFixture insertReceiveRequest(long shareAmount) {
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                ) VALUES (?, ?, '0020', 'test-snapshot', '받는 사람', ?)
                """, receiveSettlementId, receiveAccountId, CREATED_AT);
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
        return new RequestFixture(shareId, link.getCreatedAt());
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
                receiveAccountId,
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
                receiveAccountId,
                transactionId,
                CREATED_AT.plusHours(3)
        );
    }

    private String randomExternalKey() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private Long insertGroup(String name, Long ownerId) {
        Long groupId = jdbcTemplate.queryForObject("""
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
        fixtureGroupIds.add(groupId);
        return groupId;
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
