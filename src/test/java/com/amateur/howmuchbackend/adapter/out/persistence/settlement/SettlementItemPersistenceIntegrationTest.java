package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementItemJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 OCR 품목 영속 통합")
class SettlementItemPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 20, 0);

    @Autowired private SettlementExpensePersistenceAdapter expensePersistenceAdapter;
    @Autowired private SettlementItemPersistenceAdapter itemPersistenceAdapter;
    @Autowired private SettlementItemJpaRepository settlementItemJpaRepository;
    @Autowired private SettlementJpaRepository settlementJpaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        String publicId = "item_" + uniqueKey.replace("-", "").substring(0, 10);
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
                "item-persistence-" + uniqueKey,
                "품목 테스트",
                publicId,
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        settlementId = UUID.randomUUID();
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                settlementId,
                creatorId,
                "품목 영속 검증",
                30_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        )));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("V14 스키마에 OCR 품목 순서·수량·행 금액을 저장하고 조회한다")
    void receiptItems_orderQuantityAndAmount_savedAndLoaded() {
        SettlementExpense expense = saveReceiptExpense("receipt-items");

        itemPersistenceAdapter.saveAll(List.of(
                item(expense.id(), "샌드위치", 2, 20_000, 1),
                item(expense.id(), "아메리카노", 2, 10_000, 0)
        ));

        var items = itemPersistenceAdapter.findAllBySettlementId(settlementId);

        assertThat(items).extracting(SettlementItem::name)
                .containsExactly("아메리카노", "샌드위치");
        assertThat(items).extracting(SettlementItem::quantity)
                .containsExactly(2, 2);
        assertThat(items).extracting(SettlementItem::amount)
                .containsExactly(10_000L, 20_000L);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.createdAt()).isNotNull();
            assertThat(item.updatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("OCR 지출을 삭제하면 하위 품목도 Cascade로 삭제한다")
    void deletesItemsWithReceiptExpense() {
        SettlementExpense expense = saveReceiptExpense("receipt-cascade");
        SettlementItem saved = itemPersistenceAdapter.saveAll(List.of(
                item(expense.id(), "아메리카노", 2, 30_000, 0)
        )).getFirst();

        expensePersistenceAdapter.delete(expense);

        assertThat(settlementItemJpaRepository.existsById(saved.id())).isFalse();
    }

    @Test
    @DisplayName("같은 OCR 참조를 동일 정산에 두 번 저장하면 전용 충돌 오류로 변환한다")
    void rejectsDuplicateReceiptReference() {
        saveReceiptExpense("receipt-duplicate");

        assertThatThrownBy(() -> saveReceiptExpense("receipt-duplicate"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_RECEIPT_ALREADY_IMPORTED
                        ));
    }

    private SettlementExpense saveReceiptExpense(String reference) {
        return expensePersistenceAdapter.save(SettlementExpense.receiptOcr(
                settlementId,
                reference,
                "OCR 영수증",
                30_000,
                expensePersistenceAdapter.nextDisplayOrder(settlementId),
                CREATED_AT
        ));
    }

    private SettlementItem item(
            UUID expenseId,
            String name,
            int quantity,
            long amount,
            int displayOrder
    ) {
        return SettlementItem.fromReceipt(
                settlementId,
                expenseId,
                name,
                quantity,
                amount,
                displayOrder,
                CREATED_AT
        );
    }
}
