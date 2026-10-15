package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 카테고리 저장 구조(V56)")
class SettlementCategorySchemaIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);
    private static final List<String> SETTLEMENT_CODES = List.of(
            "FOOD", "CAFE_SNACK", "CONVENIENCE_MART", "ALCOHOL_ENTERTAINMENT", "SHOPPING",
            "HOBBY_LEISURE", "SUBSCRIPTION", "MEDICAL_HEALTH", "HOUSING_TELECOM", "INSURANCE_TAX_FINANCE",
            "BEAUTY", "TRANSPORT_CAR", "FLIGHT", "LODGING", "EDUCATION", "LIVING", "DONATION", "UNCATEGORIZED"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, '카테고리 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "settlement-category-" + uniqueKey,
                "cat_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                NOW, NOW, NOW
        );
        settlementId = insertSettlement();
    }

    private UUID insertSettlement() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type,
                            status, version, created_at, updated_at
                        )
                        VALUES (?, ?, '카테고리 정산', 10000, 'DUTCH_PAY', 'AWAITING_DEPOSIT', 0, ?, ?)
                        """,
                id, userId, NOW, NOW
        );
        return id;
    }

    @Test
    @DisplayName("정산용 카테고리 18개를 순서대로, 확장용 5개는 정산 미사용으로 넣는다")
    void seedsSettlementCategoriesInOrder() {
        List<String> settlementCodes = jdbcTemplate.queryForList("""
                SELECT code FROM common_code_detail
                WHERE group_code = 'SETTLEMENT_CATEGORY' AND use_yn AND attr2 = 'Y'
                ORDER BY sort_order
                """, String.class);
        List<String> extensionCodes = jdbcTemplate.queryForList("""
                SELECT code FROM common_code_detail
                WHERE group_code = 'SETTLEMENT_CATEGORY' AND attr2 = 'N'
                ORDER BY sort_order
                """, String.class);
        Integer missingDisplay = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM common_code_detail
                WHERE group_code = 'SETTLEMENT_CATEGORY'
                  AND (BTRIM(name) = '' OR description IS NULL OR attr1 IS NULL)
                """, Integer.class);

        assertThat(settlementCodes).containsExactlyElementsOf(SETTLEMENT_CODES);
        assertThat(extensionCodes).containsExactly(
                "TRANSFER", "INCOME", "CARD_PAYMENT", "SAVING_INVESTMENT", "DEFERRED_PAYMENT");
        assertThat(missingDisplay).isZero();
    }

    @Test
    @DisplayName("초기 분류 규칙은 모두 정산용 카테고리를 가리킨다")
    void seededRulesPointToSettlementCategories() {
        List<String> ruleCodes = jdbcTemplate.queryForList(
                "SELECT category_code FROM settlement_category_rules WHERE use_yn", String.class);

        assertThat(ruleCodes).hasSize(13);
        assertThat(SETTLEMENT_CODES).containsAll(ruleCodes);
        assertThat(ruleCodes).doesNotContain("UNCATEGORIZED");
    }

    @Test
    @DisplayName("대기 작업과 분류 완료 결과를 저장한다")
    void storesPendingAndClassifiedRows() {
        insertPending(settlementId);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'CLASSIFIED', category_code = 'CAFE_SNACK', source = 'RULE',
                    next_attempt_at = NULL, classified_at = ?, updated_at = ?
                WHERE settlement_id = ?
                """, NOW, NOW, settlementId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT category_code FROM settlement_categories WHERE settlement_id = ?",
                String.class, settlementId)).isEqualTo("CAFE_SNACK");
    }

    @Test
    @DisplayName("재시도를 다 쓰고 미분류로 끝나면 실패 사유를 남길 수 있다")
    void exhaustedRetryEndsUncategorizedWithFailureCode() {
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, attempt_count,
                    classified_at, last_failure_code, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'UNCATEGORIZED', 'UNCATEGORIZED', 3, ?, 'PROVIDER_ERROR', ?, ?)
                """, settlementId, NOW, NOW, NOW);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_failure_code FROM settlement_categories WHERE settlement_id = ?",
                String.class, settlementId)).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    @DisplayName("분류 완료인데 카테고리가 없으면 거부한다")
    void rejectsClassifiedWithoutCategory() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'RULE', ?, ?, ?)
                """, settlementId, NOW, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_lifecycle");
    }

    @Test
    @DisplayName("처리 중인데 선점 정보가 없으면 거부한다")
    void rejectsProcessingWithoutLease() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, attempt_count, created_at, updated_at
                ) VALUES (?, 'PROCESSING', 1, ?, ?)
                """, settlementId, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_lifecycle");
    }

    @Test
    @DisplayName("미분류 출처는 미분류 카테고리만 가질 수 있다")
    void rejectsUncategorizedSourceWithOtherCategory() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'FOOD', 'UNCATEGORIZED', ?, ?, ?)
                """, settlementId, NOW, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_uncategorized");
    }

    @Test
    @DisplayName("규칙이나 AI로 분류했는데 미분류 카테고리면 거부한다")
    void rejectsRuleSourceWithUncategorized() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'UNCATEGORIZED', 'AI', ?, ?, ?)
                """, settlementId, NOW, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_uncategorized");
    }

    @Test
    @DisplayName("재시도 상태인데 실패 사유가 없으면 거부한다")
    void rejectsRetryWithoutFailureCode() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, attempt_count, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'RETRY', 1, ?, ?, ?)
                """, settlementId, NOW, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_failure_state");
    }

    @Test
    @DisplayName("회원별 내 카테고리는 정산·회원마다 하나다")
    void rejectsDuplicateUserCategory() {
        insertUserCategory("FOOD");

        assertThatThrownBy(() -> insertUserCategory("CAFE_SNACK"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_settlement_user_categories");
    }

    @Test
    @DisplayName("작업 흐름의 정상 상태를 모두 저장할 수 있다: 선점, 재시도, 선점 만료 회수, 재시도 후 성공")
    void allowsJobLifecycle() {
        insertPending(settlementId);
        UUID firstLease = UUID.randomUUID();
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'PROCESSING', attempt_count = attempt_count + 1, next_attempt_at = NULL,
                    lease_token = ?, lease_until = ?, updated_at = ?
                WHERE settlement_id = ?
                """, firstLease, NOW.plusMinutes(1), NOW, settlementId);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'RETRY', next_attempt_at = ?, lease_token = NULL, lease_until = NULL,
                    last_failure_code = 'PROVIDER_ERROR', updated_at = ?
                WHERE settlement_id = ?
                """, NOW.plusMinutes(1), NOW, settlementId);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'PROCESSING', attempt_count = attempt_count + 1, next_attempt_at = NULL,
                    lease_token = ?, lease_until = ?, updated_at = ?
                WHERE settlement_id = ?
                """, UUID.randomUUID(), NOW.plusMinutes(2), NOW.plusMinutes(1), settlementId);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'RETRY', next_attempt_at = ?, lease_token = NULL, lease_until = NULL,
                    last_failure_code = 'PROCESSING_TIMEOUT', updated_at = ?
                WHERE settlement_id = ? AND status = 'PROCESSING' AND lease_until <= ?
                """, NOW.plusMinutes(3), NOW.plusMinutes(3), settlementId, NOW.plusMinutes(3));
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'PROCESSING', attempt_count = attempt_count + 1, next_attempt_at = NULL,
                    lease_token = ?, lease_until = ?, updated_at = ?
                WHERE settlement_id = ? AND attempt_count < max_attempts
                """, UUID.randomUUID(), NOW.plusMinutes(5), NOW.plusMinutes(4), settlementId);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'CLASSIFIED', category_code = 'FOOD', source = 'AI', lease_token = NULL,
                    lease_until = NULL, last_failure_code = NULL, classified_at = ?, updated_at = ?
                WHERE settlement_id = ?
                """, NOW.plusMinutes(4), NOW.plusMinutes(4), settlementId);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, category_code, attempt_count FROM settlement_categories WHERE settlement_id = ?",
                settlementId))
                .containsEntry("status", "CLASSIFIED")
                .containsEntry("category_code", "FOOD")
                .containsEntry("attempt_count", 3);
    }

    @Test
    @DisplayName("마지막 시도에서 성공했는데 실패 사유를 비우지 않으면 거부한다")
    void rejectsSuccessWithLeftoverFailureCode() {
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, attempt_count, lease_token, lease_until,
                    last_failure_code, created_at, updated_at
                ) VALUES (?, 'PROCESSING', 3, ?, ?, 'PROVIDER_ERROR', ?, ?)
                """, settlementId, UUID.randomUUID(), NOW.plusMinutes(1), NOW, NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'CLASSIFIED', category_code = 'FOOD', source = 'AI',
                    lease_token = NULL, lease_until = NULL, classified_at = ?, updated_at = ?
                WHERE settlement_id = ?
                """, NOW, NOW, settlementId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_failure_state");
    }

    @Test
    @DisplayName("직접 입력 정산은 시도 없이 바로 미분류로, 정기 정산은 설정 카테고리로 저장할 수 있다")
    void allowsImmediateResults() {
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'UNCATEGORIZED', 'UNCATEGORIZED', ?, ?, ?)
                """, settlementId, NOW, NOW, NOW);
        UUID recurringSettlementId = insertSettlement();
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, classified_at, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'HOUSING_TELECOM', 'RECURRING', ?, ?, ?)
                """, recurringSettlementId, NOW, NOW, NOW);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_categories WHERE settlement_id IN (?, ?) AND attempt_count = 0",
                Integer.class, settlementId, recurringSettlementId)).isEqualTo(2);
    }

    @ParameterizedTest(name = "시도 {0}회")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("시도를 다 쓰지 않고 실패 사유와 함께 미분류로 끝내면 거부한다")
    void rejectsFailureEndBeforeExhausted(int attempts) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, category_code, source, attempt_count, classified_at,
                    last_failure_code, created_at, updated_at
                ) VALUES (?, 'CLASSIFIED', 'UNCATEGORIZED', 'UNCATEGORIZED', ?, ?, 'PROVIDER_ERROR', ?, ?)
                """, settlementId, attempts, NOW, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_categories_failure_exhausted");
    }

    @Test
    @DisplayName("마지막 시도 중 선점이 만료되면 처리 시간 초과 미분류로 끝낼 수 있다")
    void exhaustedExpiredLeaseEndsWithTimeout() {
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, attempt_count, lease_token, lease_until,
                    last_failure_code, created_at, updated_at
                ) VALUES (?, 'PROCESSING', 3, ?, ?, 'PROVIDER_ERROR', ?, ?)
                """, settlementId, UUID.randomUUID(), NOW.plusMinutes(1), NOW, NOW);
        jdbcTemplate.update("""
                UPDATE settlement_categories
                SET status = 'CLASSIFIED', category_code = 'UNCATEGORIZED', source = 'UNCATEGORIZED',
                    lease_token = NULL, lease_until = NULL, last_failure_code = 'PROCESSING_TIMEOUT',
                    classified_at = ?, updated_at = ?
                WHERE settlement_id = ? AND lease_until <= ? AND attempt_count >= max_attempts
                """, NOW.plusMinutes(2), NOW.plusMinutes(2), settlementId, NOW.plusMinutes(2));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, category_code, last_failure_code FROM settlement_categories WHERE settlement_id = ?",
                settlementId))
                .containsEntry("status", "CLASSIFIED")
                .containsEntry("category_code", "UNCATEGORIZED")
                .containsEntry("last_failure_code", "PROCESSING_TIMEOUT");
    }

    @Test
    @DisplayName("대소문자·공백만 다른 규칙 키워드는 거부한다")
    void rejectsNormalizedDuplicateKeyword() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO settlement_category_rules (keyword, category_code) VALUES ('유튜브프리미엄', 'FOOD')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_settlement_category_rules_normalized_keyword");
    }

    @Test
    @DisplayName("앞뒤 공백이 있는 규칙 키워드는 거부한다")
    void rejectsUntrimmedKeyword() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO settlement_category_rules (keyword, category_code) VALUES (' 빽다방', 'CAFE_SNACK')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_category_rules_keyword");
    }

    @Test
    @DisplayName("미분류로 보내는 규칙은 거부한다")
    void rejectsUncategorizedRule() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO settlement_category_rules (keyword, category_code) VALUES ('빽다방', 'UNCATEGORIZED')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_settlement_category_rules_code");
    }

    @Test
    @DisplayName("정산이 지워지면 분류 결과와 회원별 수정값도 지워진다")
    void deletesWithSettlement() {
        insertPending(settlementId);
        insertUserCategory("FOOD");

        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_categories WHERE settlement_id = ?",
                Integer.class, settlementId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_user_categories WHERE settlement_id = ?",
                Integer.class, settlementId)).isZero();
    }

    private void insertPending(UUID id) {
        jdbcTemplate.update("""
                INSERT INTO settlement_categories (
                    settlement_id, status, next_attempt_at, created_at, updated_at
                ) VALUES (?, 'PENDING', ?, ?, ?)
                """, id, NOW, NOW, NOW);
    }

    private void insertUserCategory(String code) {
        jdbcTemplate.update("""
                INSERT INTO settlement_user_categories (
                    settlement_id, user_id, category_code, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                """, settlementId, userId, code, NOW, NOW);
    }
}
