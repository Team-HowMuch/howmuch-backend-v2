package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementLinkJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@DisplayName("공개 정산 링크 조회 영속 흐름")
class SettlementLinkPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    @Autowired private SettlementLinkPersistenceAdapter adapter;
    @Autowired private SettlementLinkJpaRepository linkRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("token hash로 V16 링크와 만료·상태 정보를 복원한다")
    void settlementLink_tokenHash_lookupLoadsExpiryAndStatus() {
        jdbcTemplate.execute("SET LOCAL TIME ZONE 'UTC'");
        String uniqueKey = UUID.randomUUID().toString();
        Long creatorId = insertUser(uniqueKey);
        UUID settlementId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);
        insertSettlement(creatorId, settlementId);
        linkRepository.saveAndFlush(new SettlementLinkJpaEntity(
                linkId,
                settlementId,
                tokenHash,
                SettlementLinkStatus.ACTIVE,
                NOW.plusDays(30)
        ));
        entityManager.clear();

        var loaded = adapter.findByTokenHash(tokenHash);

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().id()).isEqualTo(linkId);
        assertThat(loaded.orElseThrow().settlementId()).isEqualTo(settlementId);
        assertThat(loaded.orElseThrow().expiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(adapter.findByTokenHash("b".repeat(64))).isEmpty();

        LocalDateTime reissuedAt = LocalDateTime.now(ZoneOffset.UTC);
        adapter.replace(
                loaded.orElseThrow().reissue(
                        "b".repeat(64),
                        reissuedAt.plusDays(30),
                        reissuedAt
                ),
                "r".repeat(43)
        );
        entityManager.clear();

        var restored = adapter.findBySettlementId(settlementId).orElseThrow();
        assertThat(restored.link().tokenHash()).isEqualTo("b".repeat(64));
        assertThat(restored.rawToken()).isEqualTo("r".repeat(43));
    }

    private Long insertUser(String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, '최수아', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "link-entry-" + uniqueKey,
                "entry_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private void insertSettlement(Long creatorId, UUID settlementId) {
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type,
                            status, version, created_at, updated_at
                        )
                        VALUES (?, ?, '회식 정산', 10000, 'DUTCH_PAY',
                                'AWAITING_DEPOSIT', 0, ?, ?)
                        """,
                settlementId,
                creatorId,
                NOW,
                NOW
        );
    }

}
