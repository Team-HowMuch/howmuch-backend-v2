package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.recap.model.*;
import com.amateur.howmuchbackend.application.recap.service.BuildMonthlyRecapService;
import com.amateur.howmuchbackend.application.recap.service.RecapSnapshotService;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RecapCardPersistenceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BuildMonthlyRecapService builder;
    @Autowired RecapSnapshotService store;
    @Autowired MonthlyRecapPersistenceAdapter snapshots;
    @Autowired CacheManager caches;
    private SettlementUnpaidFixtures fixtures;
    private Long owner;
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final LocalDateTime COMPLETED = LocalDateTime.of(2026, 9, 6, 15, 0);
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void setUp() {
        clearCategoryCache();
        fixtures = new SettlementUnpaidFixtures(jdbc);
        owner = fixtures.user();
    }

    @AfterEach
    void clearCategoryCache() { caches.getCache("commonCodeByGroup").evict("SETTLEMENT_CATEGORY"); }

    @Test
    void cards_personalOverrideAndCompanions_useOwnCategoryAndAmountOnce() {
        Long other = fixtures.user();
        UUID settlement = completed(1000);
        fixtures.share(settlement, fixtures.participant(settlement, other), 9000);
        fixtures.participant(settlement, null);
        automaticCategory(settlement, "FOOD");
        overrideCategory(settlement, owner, "CAFE_SNACK");
        requested(settlement, COMPLETED.minusSeconds(65));

        var mine = builder.build(owner, MONTH, GENERATED).orElseThrow();
        var theirs = builder.build(other, MONTH, GENERATED).orElseThrow();
        assertThat(mine.categories()).hasSize(1);
        assertThat(mine.categories().getFirst().category()).isEqualTo(new RecapCategory("CAFE_SNACK", "카페 / 간식", "cafe-snack"));
        assertThat(mine.categories().getFirst().totalOwnAmount()).isEqualTo(1000);
        assertThat(mine.record().frequentCategory().count()).isOne();
        assertThat(mine.record().busiestWeekday().count()).isOne();
        assertThat(mine.record().fastestSettlement()).isEqualTo(new RecapRecord.FastestSettlement(1, "검증 정산"));
        assertThat(theirs.categories().getFirst().category().code()).isEqualTo("FOOD");
        assertThat(theirs.categories().getFirst().totalOwnAmount()).isEqualTo(9000);
        assertThat(store.store(mine)).isTrue();
        assertThat(snapshots.find(owner, MONTH)).contains(mine);
    }

    @Test
    void cards_sourceAndCatalogChanges_preserveStoredValuesAndDuplicateInsert() {
        UUID settlement = completed(1000);
        automaticCategory(settlement, "FOOD");
        requested(settlement, COMPLETED.minusMinutes(3));
        var original = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(store.store(original)).isTrue();
        overrideCategory(settlement, owner, "SHOPPING");
        jdbc.update("UPDATE settlements SET title='수정 제목', total_amount=9999 WHERE id=?", settlement);
        jdbc.update("UPDATE settlement_shares SET amount=9999 WHERE settlement_id=?", settlement);
        jdbc.update("UPDATE settlement_links SET created_at=? WHERE settlement_id=?", COMPLETED.minusMinutes(5), settlement);
        jdbc.update("UPDATE common_code_detail SET name='바뀐 식비', attr1='changed' WHERE group_code='SETTLEMENT_CATEGORY' AND code='FOOD'");
        clearCategoryCache();
        var rebuilt = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(rebuilt.categories()).isNotEqualTo(original.categories());
        assertThat(store.store(rebuilt)).isFalse();
        jdbc.update("UPDATE settlements SET deleted_at=? WHERE id=?", GENERATED, settlement);
        assertThat(snapshots.find(owner, MONTH)).contains(original);
        assertThat(original.withCompanionAvatar("unused.png").categories()).isEqualTo(original.categories());
        assertThat(original.withCompanionAvatar("unused.png").record()).isEqualTo(original.record());
    }

    @Test
    void cards_unclassifiedMissingOrInactiveCategory_keepNullableSections() {
        UUID uncategorized = completed(1000), unavailable = completed(2000), inactive = completed(3000);
        automaticCategory(uncategorized, "UNCATEGORIZED");
        overrideCategory(unavailable, owner, "UNKNOWN");
        automaticCategory(inactive, "FOOD");
        jdbc.update("UPDATE common_code_detail SET use_yn=false WHERE group_code='SETTLEMENT_CATEGORY' AND code='FOOD'");
        var recap = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(recap.categories()).isNull();
        assertThat(recap.record().frequentCategory()).isNull();
        assertThat(recap.record().largestSettlement().category()).isNull();
        assertThat(recap.record().fastestSettlement()).isNull();
        assertThat(recap.benefits()).isNull();
        store.store(recap);
        assertThat(snapshots.find(owner, MONTH)).contains(recap);
        assertThat(jdbc.queryForObject("SELECT categories_snapshot IS NULL AND benefits_snapshot IS NULL FROM monthly_recaps WHERE owner_user_id=?",
                Boolean.class, owner)).isTrue();
    }

    @Test
    void cards_legacySnapshot_readNullWithoutBackfill() {
        jdbc.update("""
                INSERT INTO monthly_recaps(id,owner_user_id,month_start,owner_name,completed_settlement_count,
                    total_own_amount,comparison_status,generated_at)
                VALUES (?,?,'2026-09-01','기존 회원',1,1000,'NO_PREVIOUS',?)
                """, UUID.randomUUID(), owner, GENERATED);
        completed(1000);
        var legacy = snapshots.find(owner, MONTH).orElseThrow();
        assertThat(legacy.categories()).isNull();
        assertThat(legacy.record()).isNull();
        assertThat(legacy.benefits()).isNull();
        assertThat(store.store(builder.build(owner, MONTH, GENERATED).orElseThrow())).isFalse();
        assertThat(snapshots.find(owner, MONTH)).contains(legacy);
    }

    @Test
    void benefits_explicitSnapshot_roundTripNullableLogoAndUtcExpiry() {
        completed(1000);
        var recap = builder.build(owner, MONTH, GENERATED).orElseThrow();
        var benefit = new RecapBenefit("검증용 혜택", "검증용 이유", GENERATED.plusMonths(1), "https://example.invalid/benefit", null);
        var withBenefit = new MonthlyRecap(recap.id(), recap.ownerId(), recap.yearMonth(), recap.ownerName(),
                recap.summary(), recap.comparison(), recap.companion(), recap.generatedAt(), recap.categories(), recap.record(), List.of(benefit));
        assertThat(store.store(withBenefit)).isTrue();
        assertThat(snapshots.find(owner, MONTH)).contains(withBenefit);
        assertThat(withBenefit.withCompanionAvatar("unused.png").benefits()).containsExactly(benefit);
    }

    private UUID completed(long ownAmount) {
        UUID id = fixtures.settlement(owner, SettlementStatus.COMPLETED, SettlementAllocationType.DUTCH_PAY);
        jdbc.update("UPDATE settlements SET title='검증 정산', updated_at=? WHERE id=?", COMPLETED, id);
        fixtures.share(id, fixtures.participant(id, owner), ownAmount);
        return id;
    }

    private void automaticCategory(UUID settlement, String code) {
        jdbc.update("""
                INSERT INTO settlement_categories(settlement_id,status,category_code,source,classified_at,created_at,updated_at)
                VALUES (?,'CLASSIFIED',?,?, ?,?,?)
                """, settlement, code, code.equals("UNCATEGORIZED") ? "UNCATEGORIZED" : "RULE", COMPLETED, COMPLETED, COMPLETED);
    }

    private void overrideCategory(UUID settlement, Long user, String code) {
        jdbc.update("""
                INSERT INTO settlement_user_categories(settlement_id,user_id,category_code,created_at,updated_at)
                VALUES (?,?,?,?,?)
                """, settlement, user, code, COMPLETED, COMPLETED);
    }

    private void requested(UUID settlement, LocalDateTime requested) {
        jdbc.update("""
                INSERT INTO settlement_links(id,settlement_id,token_hash,status,expires_at,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID(), settlement, UUID.randomUUID().toString().replace("-", "").repeat(2),
                GENERATED.plusDays(1), requested, requested);
    }
}
