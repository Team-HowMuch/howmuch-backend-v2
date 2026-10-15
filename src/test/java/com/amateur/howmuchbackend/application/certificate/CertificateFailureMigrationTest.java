package com.amateur.howmuchbackend.application.certificate;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

@Testcontainers
class CertificateFailureMigrationTest {
    @Container
    static final GenericContainer<?> DB =
            new GenericContainer<>(DockerImageName.parse("postgres:16.14"))
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                    .withEnv("POSTGRES_DB", "failure_upgrade")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @Test
    void upgradePreservesLegacyRowsAndOnlyAcceptsSafeFailureFacts() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://"
                                + DB.getHost()
                                + ":"
                                + DB.getMappedPort(5432)
                                + "/failure_upgrade",
                        "postgres",
                        "");
        Flyway.configure().dataSource(ds).target("45").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO certificate_registration_sessions(id,organization_code,status,pc_capability_hash,
    expires_at,last_failure_code,created_at,updated_at)
VALUES(?,'0020','FAILED',?,'2026-10-11 00:10:00','REGISTRATION_REJECTED','2026-10-11','2026-10-11')
""",
                id,
                "a".repeat(64));
        jdbc.update(
                """
INSERT INTO certificate_registration_sessions(id,organization_code,status,pc_capability_hash,
    expires_at,created_at,updated_at)
VALUES(?,'0020','CREATED',?,'2026-10-11 00:10:00','2026-10-11','2026-10-11')
""",
                UUID.randomUUID(),
                "b".repeat(64));
        String snapshot =
                "SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM"
                    + " certificate_registration_sessions s";
        String before = jdbc.queryForObject(snapshot, String.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_sessions SET"
                                            + " last_failure_code='CF-12302' WHERE id=?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        var flyway = Flyway.configure().dataSource(ds).target("46").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(snapshot, String.class)).isEqualTo(before);
        for (String code :
                new String[] {"CF-12302", "CF-13031", "CF-99998", "REGISTRATION_REJECTED"})
            assertThat(
                            jdbc.update(
                                    "UPDATE certificate_registration_sessions SET"
                                        + " last_failure_code=? WHERE id=?",
                                    code,
                                    id))
                    .isEqualTo(1);
        for (String code :
                new String[] {
                    "CF-00000",
                    "cf-12302",
                    "CF-1232",
                    "CF-12302\n",
                    "CF-12302 raw-secret",
                    " CF-12302"
                })
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE certificate_registration_sessions SET"
                                                + " last_failure_code=? WHERE id=?",
                                            code,
                                            id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(
                "UPDATE certificate_registration_sessions SET last_failure_code='CF-12302' WHERE"
                    + " id=?",
                id);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_sessions SET"
                                            + " status='CREATED' WHERE id=?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.update(
                                "UPDATE certificate_registration_sessions SET status='CANCELLED'"
                                    + " WHERE id=?",
                                id))
                .isEqualTo(1);
        String afterErrors = jdbc.queryForObject(snapshot, String.class);
        var consentUpgrade = Flyway.configure().dataSource(ds).target("47").load();
        assertThat(consentUpgrade.migrate().migrationsExecuted).isEqualTo(1);
        consentUpgrade.validate();
        assertThat(jdbc.queryForObject(snapshot, String.class)).isEqualTo(afterErrors);
        assertThat(consentUpgrade.migrate().migrationsExecuted).isZero();
    }
}
