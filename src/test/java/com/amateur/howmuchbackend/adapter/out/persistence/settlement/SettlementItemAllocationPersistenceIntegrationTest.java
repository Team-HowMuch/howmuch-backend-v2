package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 품목 배분 영속 통합")
class SettlementItemAllocationPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 24, 20, 0);

    @Autowired private SettlementItemAllocationPersistenceAdapter allocationAdapter;
    @Autowired private SettlementExpensePersistenceAdapter expenseAdapter;
    @Autowired private SettlementItemPersistenceAdapter itemAdapter;
    @Autowired private SettlementJpaRepository settlementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;
    private UUID participantId;
    private SettlementItem item;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        creatorId = jdbcTemplate.queryForObject("""
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
                "item-allocation-persistence-" + uniqueKey,
                "배분 테스트",
                "itemalloc_" + uniqueKey.replace("-", "").substring(0, 8),
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        settlementId = UUID.randomUUID();
        settlementRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                settlementId,
                creatorId,
                "품목 배분 영속 검증",
                1_000,
                SettlementAllocationType.ITEM_ALLOCATION,
                SettlementStatus.REQUESTED,
                CREATED_AT,
                CREATED_AT
        )));
        participantId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, phone_hash,
                            display_name, display_order, created_at, updated_at
                        ) VALUES (?, ?, 'REGISTERED_USER', ?, NULL, ?, 0, ?, ?)
                        """,
                participantId,
                settlementId,
                creatorId,
                "생성자",
                CREATED_AT,
                CREATED_AT
        );
        SettlementExpense expense = expenseAdapter.save(SettlementExpense.receiptOcr(
                settlementId,
                "item-allocation-" + uniqueKey,
                "영수증",
                1_000,
                0,
                CREATED_AT
        ));
        item = itemAdapter.saveAll(List.of(SettlementItem.fromReceipt(
                settlementId,
                expense.id(),
                "피자",
                1,
                1_000,
                0,
                CREATED_AT
        ))).getFirst();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("참여자의 품목 선택을 저장하고 같은 선택의 비율을 변경한다")
    void itemSelection_participantAmounts_savedAndUpdated() {
        SettlementItemSelection half = SettlementItemSelection.create(
                settlementId,
                item.id(),
                participantId,
                SettlementItemAllocationFraction.HALF,
                2,
                CREATED_AT
        );
        SettlementItemSelection saved = allocationAdapter.saveSelection(half);

        allocationAdapter.saveSelection(saved.change(
                SettlementItemAllocationFraction.ONE,
                1,
                saved.createdAt().plusMinutes(1)
        ));

        assertThat(allocationAdapter.findAllSelections(settlementId))
                .singleElement()
                .satisfies(selection -> {
                    assertThat(selection.id()).isEqualTo(saved.id());
                    assertThat(selection.fraction())
                            .isEqualTo(SettlementItemAllocationFraction.ONE);
                    assertThat(selection.units()).isEqualTo(1);
                    assertThat(selection.updatedAt()).isAfter(selection.createdAt());
                });
    }

    @Test
    @DisplayName("품목 선택을 취소하고 참여자의 제출 완료를 저장한다")
    void itemSelection_cancelAndSubmit_persisted() {
        SettlementItemSelection selection = allocationAdapter.saveSelection(
                SettlementItemSelection.create(
                        settlementId,
                        item.id(),
                        participantId,
                        SettlementItemAllocationFraction.ONE,
                        1,
                        CREATED_AT
                )
        );

        allocationAdapter.deleteSelection(selection);
        SettlementItemSubmission submission = allocationAdapter.saveSubmission(
                SettlementItemSubmission.submit(
                        settlementId,
                        participantId,
                        CREATED_AT.plusMinutes(1)
                )
        );

        assertThat(allocationAdapter.findSelection(
                settlementId,
                item.id(),
                participantId
        )).isEmpty();
        assertThat(allocationAdapter.findSubmission(
                settlementId,
                participantId
        )).contains(submission);
    }
}
