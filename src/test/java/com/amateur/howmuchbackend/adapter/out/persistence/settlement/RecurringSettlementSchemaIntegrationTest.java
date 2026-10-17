package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V59 반복 정산 스키마 제약·인덱스 검증. Docker가 없는 환경을 전제로 {@code RECURRING_TEST_DB_URL}이
 * 지정된 격리 PostgreSQL에서만 실행한다.
 */
@SpringBootTest(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@EnabledIfEnvironmentVariable(named = "RECURRING_TEST_DB_URL", matches = ".+")
@DisplayName("반복 정산 스키마(V59)")
class RecurringSettlementSchemaIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("RECURRING_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_PASSWORD", ""));
    }

    private static final List<String> TABLES = List.of(
            "recurring_settlements",
            "recurring_settlement_participants",
            "recurring_settlement_expenses",
            "recurring_settlement_occurrences");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schemaAndConstraintsExist() {
        TABLES.forEach(table -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table))
                .as(table).isEqualTo(1));

        List<String> expectedConstraints = List.of(
                "ck_recurring_settlements_category_code",
                "ck_recurring_settlements_schedule",
                "ck_recurring_settlements_failure",
                "ck_recurring_settlements_retry",
                "ck_recurring_settlements_cancellation",
                "uk_recurring_settlements_source_settlement",
                "uk_recurring_settlement_occurrences_schedule",
                "uk_recurring_settlement_occurrences_settlement");
        expectedConstraints.forEach(name -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name))
                .as(name).isEqualTo(1));

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'recurring_settlements'", String.class))
                .contains("category_code", "next_run_at", "failure_count", "retry_at");
    }

    @Test
    void dueAndRetryPartialIndexesExist() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                "idx_recurring_settlements_due")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                "idx_recurring_settlements_retry_due")).isEqualTo(1);
        String retryPredicate = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_recurring_settlements_retry_due'",
                String.class);
        assertThat(retryPredicate).contains("retry_at IS NOT NULL");
    }
}
