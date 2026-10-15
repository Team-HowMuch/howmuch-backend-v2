package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementCategoryRulePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분류 작업 저장소를 실제 DB에 붙여 검증한다. 선점 경쟁을 보려고 트랜잭션으로 감싸지 않고, 끝나면 직접 지운다.
 * 다른 테스트가 남긴 작업과 섞이지 않도록 기준 시각을 과거로 둔다.
 */
@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 분류 작업 저장소")
class SettlementCategoryJdbcAdapterIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementCategoryJobStorePort jobs;
    @Autowired private LoadSettlementCategoryRulePort rules;

    private Long userId;
    private final List<UUID> settlements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 이 테스트가 중간에 죽어 남긴 과거 시각 작업이 전역 선점에 끼지 않게 지운다.
        jdbc.update("DELETE FROM settlement_categories WHERE created_at < TIMESTAMP '2021-01-01 00:00:00'");
        String key = UUID.randomUUID().toString();
        userId = jdbc.queryForObject("""
                INSERT INTO users (identity_key, name, public_id, phone_number, pin_hash, phone_verified, role,
                    active, required_terms_agreed_at, created_at, updated_at)
                VALUES (?, '분류 작업 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, "category-job-" + key, "cj_" + key.replace("-", "").substring(0, 12),
                "cj-" + key.substring(0, 12), NOW, NOW, NOW);
    }

    @AfterEach
    void tearDown() {
        settlements.forEach(id -> jdbc.update("DELETE FROM settlements WHERE id = ?", id));
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("작업은 정산당 하나이고, 이미 있으면 새로 만들지 않는다")
    void schedule_isIdempotent() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        jobs.saveUncategorized(id, NOW.plusMinutes(1));
        jobs.schedule(id, NOW.plusMinutes(2));

        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);

        UUID manual = settlement();
        jobs.saveUncategorized(manual, NOW);
        assertThat(row(manual)).containsEntry("status", "CLASSIFIED").containsEntry("category_code", "UNCATEGORIZED")
                .containsEntry("source", "UNCATEGORIZED").containsEntry("attempt_count", 0);
    }

    @Test
    @DisplayName("대기 시각이 된 작업만 선점하고, 선점하면 시도 횟수를 올린다")
    void claim_dueOnlyAndCountsAttempt() {
        UUID id = settlement();
        jobs.schedule(id, NOW.plusMinutes(1));

        assertThat(jobs.claimDue(NOW, NOW.plusMinutes(2))).isEmpty();
        var claim = jobs.claimDue(NOW.plusMinutes(1), NOW.plusMinutes(3)).orElseThrow();

        assertThat(claim.settlementId()).isEqualTo(id);
        assertThat(claim.attemptCount()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "PROCESSING");
        assertThat(jobs.claimDue(NOW.plusMinutes(1), NOW.plusMinutes(3))).isEmpty();
    }

    @Test
    @DisplayName("여러 워커가 동시에 선점해도 한 작업은 한 워커만 가져간다")
    void concurrentClaims_takeEachJobOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = settlement();
            jobs.schedule(id, NOW);
            ids.add(id);
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Optional<SettlementCategoryJob>>> claims = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                claims.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return jobs.claimDue(NOW, NOW.plusMinutes(2));
                }, pool));
            }
            start.countDown();
            List<UUID> claimed = new ArrayList<>();
            for (var claim : claims) {
                claim.get(20, TimeUnit.SECONDS).ifPresent(job -> claimed.add(job.settlementId()));
            }
            assertThat(claimed).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("분류 결과를 저장하면 실패 사유를 비운다")
    void classify_clearsFailure() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        var first = jobs.claimDue(NOW, NOW.plusMinutes(2)).orElseThrow();
        assertThat(jobs.retry(first, NOW.plusMinutes(1), SettlementCategoryFailureCode.PROVIDER_ERROR, NOW)).isTrue();
        assertThat(row(id)).containsEntry("status", "RETRY").containsEntry("last_failure_code", "PROVIDER_ERROR");

        var second = jobs.claimDue(NOW.plusMinutes(1), NOW.plusMinutes(3)).orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(jobs.classify(second, "FOOD", SettlementCategorySource.AI, NOW.plusMinutes(1))).isTrue();

        assertThat(row(id)).containsEntry("status", "CLASSIFIED").containsEntry("category_code", "FOOD")
                .containsEntry("source", "AI").containsEntry("attempt_count", 2);
        assertThat(row(id).get("last_failure_code")).isNull();
    }

    @Test
    @DisplayName("선점이 끝난 뒤 늦게 온 결과는 저장하지 않는다")
    void expiredClaim_cannotWrite() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        var claim = jobs.claimDue(NOW, NOW.plusMinutes(2)).orElseThrow();

        assertThat(jobs.classify(claim, "FOOD", SettlementCategorySource.AI, NOW.plusMinutes(2))).isFalse();
        assertThat(jobs.retry(claim, NOW.plusMinutes(3), SettlementCategoryFailureCode.PROVIDER_ERROR,
                NOW.plusMinutes(2))).isFalse();
        assertThat(jobs.discard(claim, NOW.plusMinutes(2))).isFalse();
        assertThat(row(id)).containsEntry("status", "PROCESSING");
    }

    @Test
    @DisplayName("다른 선점 토큰으로는 결과를 저장하지 못한다")
    void foreignClaim_cannotWrite() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        var claim = jobs.claimDue(NOW, NOW.plusMinutes(2)).orElseThrow();
        var forged = new SettlementCategoryJob(id, claim.attemptCount(), claim.maxAttempts(), UUID.randomUUID(),
                claim.leaseUntil());

        assertThat(jobs.classify(forged, "FOOD", SettlementCategorySource.RULE, NOW)).isFalse();
        assertThat(row(id)).containsEntry("status", "PROCESSING");
    }

    @Test
    @DisplayName("만료된 선점은 시도가 남았으면 처리 시간 초과 재시도로 돌리고 다음 선점에서 횟수를 올린다")
    void expiredLease_returnsToRetry() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        jobs.claimDue(NOW, NOW.plusMinutes(2)).orElseThrow();

        var reclaimed = jobs.claimDue(NOW.plusMinutes(2), NOW.plusMinutes(4)).orElseThrow();

        assertThat(reclaimed.settlementId()).isEqualTo(id);
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(row(id)).containsEntry("status", "PROCESSING")
                .containsEntry("last_failure_code", "PROCESSING_TIMEOUT");
    }

    @Test
    @DisplayName("마지막 시도 중 선점이 만료되면 처리 시간 초과 미분류로 끝낸다")
    void expiredLastLease_endsUncategorized() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        LocalDateTime at = NOW;
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = jobs.claimDue(at, at.plusMinutes(2)).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            at = at.plusMinutes(2);
        }

        assertThat(jobs.claimDue(at, at.plusMinutes(2))).isEmpty();
        assertThat(row(id)).containsEntry("status", "CLASSIFIED").containsEntry("category_code", "UNCATEGORIZED")
                .containsEntry("source", "UNCATEGORIZED").containsEntry("last_failure_code", "PROCESSING_TIMEOUT")
                .containsEntry("attempt_count", 3);
    }

    @Test
    @DisplayName("시도를 다 쓰면 실패 사유와 함께 미분류로 끝내고, 지워진 정산의 작업은 지운다")
    void giveUpAndDiscard() {
        UUID id = settlement();
        jobs.schedule(id, NOW);
        LocalDateTime at = NOW;
        SettlementCategoryJob claim = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            claim = jobs.claimDue(at, at.plusMinutes(2)).orElseThrow();
            if (attempt < 3) {
                jobs.retry(claim, at, SettlementCategoryFailureCode.PROVIDER_ERROR, at);
            }
        }
        assertThat(jobs.giveUp(claim, SettlementCategoryFailureCode.INVALID_RESPONSE, at)).isTrue();
        assertThat(row(id)).containsEntry("status", "CLASSIFIED").containsEntry("category_code", "UNCATEGORIZED")
                .containsEntry("last_failure_code", "INVALID_RESPONSE");

        UUID deleted = settlement();
        jobs.schedule(deleted, NOW);
        var deletedClaim = jobs.claimDue(NOW, NOW.plusMinutes(2)).orElseThrow();
        assertThat(jobs.discard(deletedClaim, NOW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_categories WHERE settlement_id = ?",
                Integer.class, deleted)).isZero();
    }

    @Test
    @DisplayName("사용 중인 분류 규칙을 읽는다")
    void rules_loadActive() {
        assertThat(rules.findActive()).extracting(SettlementCategoryRule::keyword)
                .contains("스타벅스", "CU", "유튜브 프리미엄", "카카오T");
    }

    private UUID settlement() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements (id, creator_id, title, total_amount, allocation_type, status, version,
                    created_at, updated_at)
                VALUES (?, ?, '분류 작업', 10000, 'DUTCH_PAY', 'AWAITING_DEPOSIT', 0, ?, ?)
                """, id, userId, NOW, NOW);
        settlements.add(id);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, category_code, source, attempt_count, last_failure_code
                FROM settlement_categories WHERE settlement_id = ?
                """, id);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
