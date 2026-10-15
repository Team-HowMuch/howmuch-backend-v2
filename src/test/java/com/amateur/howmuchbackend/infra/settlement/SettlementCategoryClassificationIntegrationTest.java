package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Outcome;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Result;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementCategoryClassificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 분류 폴러를 켠 설정으로 컨텍스트를 띄우고, 실제 DB의 규칙·공통 코드로 작업을 처리한다.
 * 입금 대조 폴러도 같이 켜서 공용 scheduler 빈이 겹치지 않는지 본다. AI 분류기만 대신한다.
 */
@SpringBootTest(properties = {
        "howmuch.settlement.category-classification.enabled=true",
        "howmuch.settlement.category-classification.poll-interval=1d",
        "howmuch.settlement.category-classification.batch-size=1",
        "howmuch.settlement.transfer-reconciliation.enabled=true",
        "howmuch.settlement.transfer-reconciliation.poll-interval=1d",
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused"
})
@DisplayName("정산 카테고리 분류 폴러")
class SettlementCategoryClassificationIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2019, 6, 1, 0, 0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementCategoryJobStorePort jobs;
    @Autowired private SettlementCategoryClassificationScheduler scheduler;
    @Autowired private SettlementCategoryClassificationProperties properties;
    @Autowired @Qualifier("settlementCategoryClassificationTaskScheduler") private ThreadPoolTaskScheduler categoryScheduler;
    @Autowired @Qualifier("settlementTransferReconciliationTaskScheduler") private ThreadPoolTaskScheduler transferScheduler;
    @Autowired @Qualifier("taskScheduler") private ThreadPoolTaskScheduler commonScheduler;
    @MockitoBean private Clock clock;
    @MockitoBean private ClassifySettlementCategoryPort classifier;

    private Long userId;
    private final List<UUID> settlements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(classifier.available()).thenReturn(true);
        // 이 테스트가 중간에 죽어 남긴 과거 시각 작업이 전역 선점에 끼지 않게 지운다.
        jdbc.update("DELETE FROM settlement_categories WHERE created_at < TIMESTAMP '2021-01-01 00:00:00'");
        String key = UUID.randomUUID().toString();
        userId = jdbc.queryForObject("""
                INSERT INTO users (identity_key, name, public_id, phone_number, pin_hash, phone_verified, role,
                    active, required_terms_agreed_at, created_at, updated_at)
                VALUES (?, '분류 폴러 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, "category-poller-" + key, "cp_" + key.replace("-", "").substring(0, 12),
                "cp-" + key.substring(0, 12), NOW, NOW, NOW);
    }

    @AfterEach
    void tearDown() {
        settlements.forEach(id -> jdbc.update("DELETE FROM settlements WHERE id = ?", id));
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("켜진 설정에서 폴러와 전용 scheduler가 뜨고, 입금 대조 폴러와 공용 scheduler를 함께 쓴다")
    void enabledContext_wiresSchedulers() {
        assertThat(properties.batchSize()).isEqualTo(1);
        assertThat(categoryScheduler.getThreadNamePrefix()).isEqualTo("settlement-category-classification-");
        assertThat(transferScheduler).isNotSameAs(categoryScheduler);
        assertThat(commonScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
    }

    @Test
    @DisplayName("가게 이름이 규칙에 맞으면 실제 규칙 사전으로 분류하고 AI를 부르지 않는다")
    void poll_ruleFromSeededDictionary() {
        UUID id = receiptSettlement("스타벅스 강남R점", "아메리카노");
        jobs.schedule(id, NOW);

        scheduler.processDue();

        assertThat(row(id)).containsEntry("status", "CLASSIFIED").containsEntry("category_code", "CAFE_SNACK")
                .containsEntry("source", "RULE");
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("짧은 영문 규칙은 다른 단어 안에서 맞지 않고 AI로 넘어간다")
    void poll_shortKeywordNotInsideWord() {
        UUID id = receiptSettlement("CUBE 보드게임카페", "음료");
        jobs.schedule(id, NOW);
        when(classifier.classify(any())).thenReturn(Result.classified("HOBBY_LEISURE"));

        scheduler.processDue();

        assertThat(row(id)).containsEntry("category_code", "HOBBY_LEISURE").containsEntry("source", "AI");
        ArgumentCaptor<ClassifySettlementCategoryPort.Request> request =
                ArgumentCaptor.forClass(ClassifySettlementCategoryPort.Request.class);
        verify(classifier).classify(request.capture());
        assertThat(request.getValue().merchantNames()).containsExactly("CUBE 보드게임카페");
        assertThat(request.getValue().itemNames()).containsExactly("음료");
        assertThat(request.getValue().candidates()).hasSize(17)
                .extracting(ClassifySettlementCategoryPort.Candidate::code)
                .startsWith("FOOD", "CAFE_SNACK").doesNotContain("UNCATEGORIZED", "TRANSFER");
    }

    @Test
    @DisplayName("AI 호출이 실패하면 재시도로 돌리고, 지워진 정산의 작업은 지운다")
    void poll_failureAndDeletedSettlement() {
        UUID failing = receiptSettlement("행복상회", "물건");
        jobs.schedule(failing, NOW);
        when(classifier.classify(any())).thenReturn(Result.of(Outcome.PROVIDER_ERROR));

        scheduler.processDue();

        assertThat(row(failing)).containsEntry("status", "RETRY").containsEntry("last_failure_code", "PROVIDER_ERROR")
                .containsEntry("attempt_count", 1);

        UUID deleted = receiptSettlement("스타벅스", "라떼");
        jobs.schedule(deleted, NOW.minusMinutes(1));
        jdbc.update("UPDATE settlements SET deleted_at = ? WHERE id = ?", NOW, deleted);

        scheduler.processDue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_categories WHERE settlement_id = ?",
                Integer.class, deleted)).isZero();
    }

    private UUID receiptSettlement(String storeName, String itemName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements (id, creator_id, title, total_amount, allocation_type, status, version,
                    created_at, updated_at)
                VALUES (?, ?, '분류 폴러', 10000, 'DUTCH_PAY', 'AWAITING_DEPOSIT', 0, ?, ?)
                """, id, userId, NOW, NOW);
        settlements.add(id);
        UUID expenseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_expenses (id, settlement_id, source, source_reference, description, amount,
                    display_order, created_at, updated_at)
                VALUES (?, ?, 'RECEIPT_OCR', ?, ?, 10000, 0, ?, ?)
                """, expenseId, id, "receipt-" + expenseId, storeName, NOW, NOW);
        jdbc.update("""
                INSERT INTO settlement_items (id, settlement_id, expense_id, name, quantity, amount, display_order,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 10000, 0, ?, ?)
                """, UUID.randomUUID(), id, expenseId, itemName, NOW, NOW);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, category_code, source, attempt_count, last_failure_code
                FROM settlement_categories WHERE settlement_id = ?
                """, id);
    }
}
