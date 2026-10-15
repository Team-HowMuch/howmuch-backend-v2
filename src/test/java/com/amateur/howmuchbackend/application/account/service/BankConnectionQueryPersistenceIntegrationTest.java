package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.port.out.external.LoadBankConnectionsPort;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@Transactional
class BankConnectionQueryPersistenceIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadBankConnectionsPort connections;
    @Autowired private CodefConfigurationFingerprint fingerprint;

    @Test
    void projectionScopesOwnerActiveFlagAndCurrentProviderConfigurationWithoutDecrypting() {
        String unique = UUID.randomUUID().toString();
        var now = LocalDateTime.of(2026, 10, 11, 0, 0);
        Long user = jdbc.queryForObject("""
                INSERT INTO users (identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)
                VALUES (?, '연결 조회 fixture', ?, ?, 'unused',true,'USER',true,?,?) RETURNING id
                """, Long.class, "query-" + unique, "query_" + unique.replace("-", "").substring(0,12), "test-" + unique.substring(0,12), now, now);
        Long connection = jdbc.queryForObject("""
                INSERT INTO external_connections (user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)
                VALUES (?, 'not-decryptable-fixture', ?, true, ?, ?) RETURNING id
                """, Long.class, user, fingerprint.current(), now, now);
        jdbc.update("""
                INSERT INTO connected_institutions (external_connection_id,organization,status,created_at,updated_at)
                VALUES (?, '0020', 'CONNECTED', ?, ?), (?, '0088', 'CONNECTED', ?, ?)
                """, connection, now, now, connection, now.plusSeconds(1), now.plusSeconds(1));
        var result = connections.findActiveByUserId(user);
        assertThat(result).extracting(item -> item.organization()).containsExactly("0088", "0020");
        assertThat(result.getFirst().connectionId()).isEqualTo(connection);
        assertThat(connections.findActiveByUserId(-1L)).isEmpty();
        jdbc.update("UPDATE external_connections SET active=false WHERE id=?", connection);
        assertThat(connections.findActiveByUserId(user)).isEmpty();
        jdbc.update("UPDATE external_connections SET active=true,configuration_fingerprint=? WHERE id=?", "different-config", connection);
        assertThat(connections.findActiveByUserId(user)).isEmpty();
    }
}
