package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
class AdminUserDeletionIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);
    @Autowired private AdminUserDeletionService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentActorPort currentActorPort;
    @MockitoSpyBean private AuthAuditPort authAuditPort;
    private Long adminId;
    private Long targetId;
    private String targetPhone;

    @BeforeEach
    void setUp() {
        adminId = insertUser("ADMIN", randomPhone());
        targetPhone = randomPhone();
        targetId = insertUser("USER", targetPhone);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(adminId));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM auth_audit_logs WHERE user_id = ? OR target_user_id = ?", adminId, targetId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", adminId, targetId);
    }

    @Test
    void searchAndDeleteAnonymizesTargetAndPersistsAudit() {
        String formattedPhone = targetPhone.substring(0, 3) + "-"
                + targetPhone.substring(3, 7) + "-" + targetPhone.substring(7);
        assertThat(service.search(new SearchAdminUserCommand(formattedPhone)).userId()).isEqualTo(targetId);

        service.delete(targetId);

        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, targetId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT phone_number FROM users WHERE id = ?", String.class, targetId))
                .isNotEqualTo(targetPhone);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_audit_logs
                WHERE user_id = ? AND target_user_id = ? AND event_type = 'ADMIN_USER_DELETION'
                AND occurred_at IS NOT NULL
                """, Integer.class, adminId, targetId)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackUserAnonymizationAndAuditInsert() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated audit failure");
        }).when(authAuditPort).recordAdminUserDeletion(eq(adminId), eq(targetId), any(LocalDateTime.class));

        assertThatThrownBy(() -> service.delete(targetId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, targetId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT phone_number FROM users WHERE id = ?", String.class, targetId))
                .isEqualTo(targetPhone);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_audit_logs WHERE target_user_id = ?",
                Integer.class, targetId)).isZero();
    }

    private Long insertUser(String role, String phone) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbc.queryForObject("""
                INSERT INTO users (identity_key, name, public_id, phone_number, pin_hash,
                                   phone_verified, role, active, required_terms_agreed_at,
                                   created_at, updated_at)
                VALUES (?, '테스트 사용자', ?, ?, 'test-pin-hash', TRUE, ?, TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, "admin-delete-test-" + suffix, "user" + suffix, phone,
                role, NOW, NOW, NOW);
    }

    private String randomPhone() {
        return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
