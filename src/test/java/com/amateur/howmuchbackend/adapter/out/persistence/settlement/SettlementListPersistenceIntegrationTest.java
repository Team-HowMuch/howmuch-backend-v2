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
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
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
    void loadsReceiveSettlementSummary() {
        var result = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
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
            assertThat(item.requestedAt()).isEqualTo(receiveCreatedAt);
            assertThat(item.amountToPay()).isNull();
            assertThat(item.groups()).isEmpty();
        });
    }

    @Test
    @DisplayName("RECEIVE는 실제 완납 참여자 수와 링크 생성 시각을 조회한다")
    void loadsCompletedParticipantCountAndRequestedAt() {
        RequestFixture request = insertReceiveRequest(600L);
        insertDeposit(request.shareId(), 300L);

        var partiallyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null
        );

        assertThat(partiallyPaid).singleElement().satisfies(item -> {
            assertThat(item.payableParticipantCount()).isEqualTo(1);
            assertThat(item.completedParticipantCount()).isZero();
            assertThat(item.requestedAt()).isEqualTo(request.requestedAt());
        });

        insertDeposit(request.shareId(), 300L);

        var fullyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                null
        );

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

        var creatorOnlyPaid = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
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
                null
        );

        assertThat(payerPaid).singleElement()
                .extracting(item -> item.completedParticipantCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PAY는 연결 참여자의 Share 금액과 요청자 이름을 조회한다")
    void loadsPaySettlementSummary() {
        var result = settlementPersistenceAdapter.findAll(
                payerId,
                SettlementListTab.PAY,
                SettlementListStatusFilter.AWAITING_DEPOSIT,
                null
        );

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

        var matching = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                groupId
        );
        var notMatching = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                unauthorizedGroupId
        );

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
