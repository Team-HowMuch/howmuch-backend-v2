package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 낙관적 락 통합")
class SettlementOptimisticLockIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 21, 9, 0);

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        creatorId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key,
                            name,
                            nickname,
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
                "settlement-lock-" + uniqueKey,
                "동시성 테스트",
                "동시성테스트",
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
                "회식 정산",
                10_000,
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
    @DisplayName("앞선 변경을 잃지 않도록 같은 version에서 시작한 두 저장 중 후속 저장을 거부한다")
    void rejectsStaleConcurrentUpdate() throws Exception {
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        CountDownLatch titleCommitted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> titleUpdate = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        SettlementJpaEntity entity = findSettlement();
                        await(bothLoaded);
                        Settlement updated = SettlementMapper.toDomain(entity)
                                .updateDraft("최종 회식 정산", null, CREATED_AT.plusMinutes(1));
                        entity.updateFrom(updated);
                    });
                    return null;
                } finally {
                    titleCommitted.countDown();
                }
            });

            Future<Void> amountUpdate = executor.submit(() -> {
                transaction.executeWithoutResult(status -> {
                    SettlementJpaEntity entity = findSettlement();
                    await(bothLoaded);
                    await(titleCommitted);
                    Settlement updated = SettlementMapper.toDomain(entity)
                            .updateDraft(null, 20_000L, CREATED_AT.plusMinutes(1));
                    entity.updateFrom(updated);
                });
                return null;
            });

            assertThatCode(() -> titleUpdate.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatThrownBy(() -> amountUpdate.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }

        SettlementJpaEntity persisted = findSettlement();
        assertThat(persisted.getTitle()).isEqualTo("최종 회식 정산");
        assertThat(persisted.getTotalAmount()).isEqualTo(10_000);
        assertThat(persisted.getStatus()).isEqualTo(SettlementStatus.DRAFT);
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    private SettlementJpaEntity findSettlement() {
        return settlementJpaRepository.findById(settlementId).orElseThrow();
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("동시 저장 준비에 실패했습니다.", exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("선행 정산 저장을 기다리는 데 실패했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("선행 정산 저장 대기가 중단되었습니다.", exception);
        }
    }
}
