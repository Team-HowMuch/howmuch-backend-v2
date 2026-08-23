package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementExpenseJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
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
@DisplayName("정산 지출 영속 통합")
class SettlementExpensePersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 12, 0);

    @Autowired
    private SettlementExpensePersistenceAdapter settlementExpensePersistenceAdapter;

    @Autowired
    private SettlementExpenseJpaRepository settlementExpenseJpaRepository;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        String publicId = "expense_" + uniqueKey.replace("-", "").substring(0, 10);
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
                "expense-persistence-" + uniqueKey,
                "지출 테스트",
                publicId,
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(
                settlementId,
                creatorId,
                "지출 영속 검증",
                50_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(settlement));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("V11 스키마에 지출을 표시 순서대로 저장하고 누적액을 계산한다")
    void savesAndLoadsExpenses() {
        settlementExpensePersistenceAdapter.save(manualExpense("카페", 8_000, 1));
        settlementExpensePersistenceAdapter.save(manualExpense("저녁 식사", 42_000, 0));

        var expenses = settlementExpensePersistenceAdapter.findAllBySettlementId(settlementId);

        assertThat(expenses).extracting(SettlementExpense::description)
                .containsExactly("저녁 식사", "카페");
        assertThat(settlementExpensePersistenceAdapter.totalAmount(settlementId))
                .isEqualTo(50_000);
        assertThat(settlementExpensePersistenceAdapter.nextDisplayOrder(settlementId))
                .isEqualTo(2);
        assertThat(expenses).allSatisfy(expense -> {
            assertThat(expense.createdAt()).isNotNull();
            assertThat(expense.updatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("지출 수정과 삭제가 최신 누적액에 반영된다")
    void updatesAndDeletesExpense() {
        SettlementExpense saved = settlementExpensePersistenceAdapter.save(
                manualExpense("저녁 식사", 42_000, 0)
        );
        SettlementExpense updated = saved.updateManual(
                "저녁 식사와 음료",
                50_000L,
                CREATED_AT.plusMinutes(1)
        );

        SettlementExpense persisted = settlementExpensePersistenceAdapter.update(updated);
        assertThat(persisted.description()).isEqualTo("저녁 식사와 음료");
        assertThat(settlementExpensePersistenceAdapter.totalAmount(settlementId))
                .isEqualTo(50_000);

        settlementExpensePersistenceAdapter.delete(persisted);

        assertThat(settlementExpenseJpaRepository.existsById(saved.id())).isFalse();
        assertThat(settlementExpensePersistenceAdapter.totalAmount(settlementId)).isZero();
    }

    @Test
    @DisplayName("정산 삭제 시 하위 지출도 함께 삭제한다")
    void deletesExpenseWithSettlement() {
        SettlementExpense saved = settlementExpensePersistenceAdapter.save(
                manualExpense("저녁 식사", 42_000, 0)
        );

        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);

        assertThat(settlementExpenseJpaRepository.existsById(saved.id())).isFalse();
    }

    private SettlementExpense manualExpense(String description, long amount, int displayOrder) {
        return SettlementExpense.manual(
                settlementId,
                description,
                amount,
                displayOrder,
                CREATED_AT
        );
    }
}
