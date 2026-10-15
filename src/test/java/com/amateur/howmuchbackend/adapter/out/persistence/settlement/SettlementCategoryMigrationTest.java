package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DisplayName("정산 카테고리 마이그레이션(V55 -> V56)")
class SettlementCategoryMigrationTest {
    @Container
    static final GenericContainer<?> DB =
            new GenericContainer<>(DockerImageName.parse("postgres:16.14"))
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                    .withEnv("POSTGRES_DB", "category_upgrade")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @Test
    @DisplayName("기존 정산과 공통 코드를 그대로 두고 카테고리 구조만 추가한다")
    void upgradeKeepsExistingRowsAndAddsCategorySchema() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/category_upgrade",
                "postgres",
                ""
        );
        Flyway.configure().dataSource(dataSource).target("55").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 3, 0);
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (
                    identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, required_terms_agreed_at, created_at, updated_at
                ) VALUES ('category-upgrade', '업그레이드', 'category_upgrade', 'test-upgrade', 'not-used',
                    TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, now, now, now);
        UUID settlementId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements (
                    id, creator_id, title, total_amount, allocation_type, status, version, created_at, updated_at
                ) VALUES (?, ?, '배포 전 정산', 10000, 'DUTCH_PAY', 'AWAITING_DEPOSIT', 0, ?, ?)
                """, settlementId, userId, now, now);
        Integer bankCodes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_code_detail WHERE group_code = 'BANK'", Integer.class);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlements WHERE id = ?", Integer.class, settlementId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_categories WHERE settlement_id = ?",
                Integer.class, settlementId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_code_detail WHERE group_code = 'BANK'", Integer.class))
                .isEqualTo(bankCodes);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_code_detail WHERE group_code = 'SETTLEMENT_CATEGORY'",
                Integer.class)).isEqualTo(23);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_category_rules", Integer.class)).isEqualTo(13);
    }
}
