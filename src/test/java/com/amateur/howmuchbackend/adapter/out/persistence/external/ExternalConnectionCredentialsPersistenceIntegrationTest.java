package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=aG93bXVjaC1jb2RlZi1tb2NrLWtleS0zMi1ieXRlcyE="
})
class ExternalConnectionCredentialsPersistenceIntegrationTest {
    @Autowired private ExternalConnectionPersistenceAdapter adapter;
    @Autowired private JdbcTemplate jdbc;
    private Long userId;
    private Long connectionId;

    @Test
    void encryptedCredentialsPersistUpdateAndClear() {
        String unique = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 0, 0);
        userId = jdbc.queryForObject("""
                INSERT INTO users (identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, required_terms_agreed_at, created_at, updated_at)
                VALUES (?, '암호문 테스트', ?, ?, 'unused', TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, "credential-" + unique, "cred_" + unique.replace("-", "").substring(0, 12),
                "test-" + unique.substring(0, 12), now, now, now);
        var connection = adapter.saveNew(new ExternalConnection(null, userId, "CID-TEST"),
                new ConnectedInstitution(null, null, "0020", ExternalConnectionStatus.CONNECTED,
                        "CF-00000", "local:v1:encrypted", "kms:v1:encrypted"));
        connectionId = connection.id();

        assertThat(adapter.findInstitution(connectionId, "0020")).get()
                .extracting(ConnectedInstitution::localCredentialsCiphertext)
                .isEqualTo("local:v1:encrypted");
        assertThat(jdbc.queryForObject("""
                SELECT account_credentials_kms_ciphertext FROM connected_institutions
                WHERE external_connection_id = ? AND organization = '0020'
                """, String.class, connectionId)).isEqualTo("kms:v1:encrypted");

        adapter.updateInstitutionCredentials(connectionId, "0020", "local:v1:updated", null);
        adapter.saveKmsCredentialsIfAbsent(connectionId, "0020", "kms:v1:backfilled");
        assertThat(adapter.findInstitution(connectionId, "0020")).get()
                .extracting(ConnectedInstitution::kmsCredentialsCiphertext)
                .isEqualTo("kms:v1:backfilled");

        adapter.clearCredentialsByUserId(userId);
        assertThat(adapter.findInstitution(connectionId, "0020")).get()
                .extracting(ConnectedInstitution::localCredentialsCiphertext,
                        ConnectedInstitution::kmsCredentialsCiphertext)
                .containsExactly(null, null);
    }

    @AfterEach
    void cleanUp() {
        if (connectionId != null) {
            jdbc.update("DELETE FROM connected_institutions WHERE external_connection_id = ?", connectionId);
            jdbc.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
