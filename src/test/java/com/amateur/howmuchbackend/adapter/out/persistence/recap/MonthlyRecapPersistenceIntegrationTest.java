package com.amateur.howmuchbackend.adapter.out.persistence.recap;

import com.amateur.howmuchbackend.application.recap.service.BuildMonthlyRecapService;
import com.amateur.howmuchbackend.application.recap.service.RecapSnapshotService;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class MonthlyRecapPersistenceIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BuildMonthlyRecapService builder;
    @Autowired private RecapSnapshotService store;
    @Autowired private MonthlyRecapPersistenceAdapter snapshots;
    @Autowired private RecapSourcePersistenceAdapter source;
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 10, 1, 0, 0);

    @Test
    void countsOwnShareOnceAcrossCreatorAndParticipantRolesAndCompanions() {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user(), other = f.user();
        UUID first = completed(f, owner, LocalDateTime.of(2026, 9, 10, 0, 0));
        f.share(first, f.participant(first, owner), 10_000);
        f.share(first, f.participant(first, other), 30_000);
        f.participant(first, null);
        UUID second = completed(f, other, LocalDateTime.of(2026, 9, 15, 0, 0));
        f.share(second, f.participant(second, owner), 5_000);
        f.participant(second, other);
        var recap = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(recap.summary().completedSettlementCount()).isEqualTo(2);
        assertThat(recap.summary().totalOwnAmount()).isEqualTo(15_000);
        assertThat(recap.companion().identityKey()).isEqualTo("MEMBER:" + other);
        assertThat(recap.companion().completedSettlementCount()).isEqualTo(2);
        assertThat(recap.companion().totalOwnAmount()).isEqualTo(15_000);
    }

    @ParameterizedTest
    @CsvSource({"2025-12,2025-11-30T15:00:00,2025-12-31T15:00:00",
            "2024-02,2024-01-31T15:00:00,2024-02-29T15:00:00", "2026-09,2026-08-31T15:00:00,2026-09-30T15:00:00"})
    void includesKstStartAndExcludesNextStart(String monthText, String startText, String endText) {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user();
        LocalDateTime start = LocalDateTime.parse(startText), end = LocalDateTime.parse(endText);
        for (LocalDateTime at : new LocalDateTime[]{start.minusNanos(1000), start, end.minusNanos(1000), end}) {
            UUID id = completed(f, owner, at);
            f.share(id, f.participant(id, owner), 100);
        }
        var facts = source.findCompletedMonth(owner, YearMonth.parse(monthText));
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(fact -> fact.completedAt()).containsExactlyInAnyOrder(start, end.minusNanos(1000));
    }

    @Test
    void zeroSubmissionGeneratesButMissingShareFails() {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user();
        UUID id = f.settlement(owner, SettlementStatus.COMPLETED, SettlementAllocationType.ITEM_ALLOCATION);
        jdbc.update("UPDATE settlements SET updated_at = ? WHERE id = ?", GENERATED.minusDays(2), id);
        UUID participant = f.participant(id, owner);
        assertThatThrownBy(() -> builder.build(owner, MONTH, GENERATED)).isInstanceOf(IllegalStateException.class);
        f.submitZero(id, participant);
        Long other = f.user();
        UUID otherParticipant = f.participant(id, other);
        f.submitZero(id, otherParticipant);
        UUID item = item(id, 1, 100, 0);
        select(id, item, otherParticipant, "ONE", 1);
        jdbc.update("UPDATE settlements SET total_amount = 100 WHERE id = ?", id);
        var recap = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(recap.summary().completedSettlementCount()).isOne();
        assertThat(recap.summary().totalOwnAmount()).isZero();
        assertThat(recap.companion().completedSettlementCount()).isOne();
        assertThat(store.store(recap)).isTrue();
    }

    @Test
    void excludesDeletedIncompleteAndPhoneOnlyParticipation() {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user(), other = f.user();
        UUID deleted = completed(f, owner, GENERATED.minusDays(2));
        f.participant(deleted, owner); f.markDeleted(deleted);
        for (var status : SettlementStatus.values()) {
            if (status == SettlementStatus.COMPLETED) continue;
            UUID id = f.settlement(owner, status, SettlementAllocationType.DUTCH_PAY);
            jdbc.update("UPDATE settlements SET updated_at = ? WHERE id = ?", GENERATED.minusDays(2), id);
            f.participant(id, owner);
        }
        UUID notLinked = completed(f, other, GENERATED.minusDays(2));
        f.participant(notLinked, null, "f".repeat(64));
        assertThat(builder.build(owner, MONTH, GENERATED)).isEmpty();
    }

    @Test
    void snapshotsRemainFixedAfterOriginalChangeAndCompareOnlyImmediateStoredMonth() {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user(), companion = f.user();
        UUID id = completed(f, owner, LocalDateTime.of(2026, 8, 20, 0, 0));
        UUID share = f.share(id, f.participant(id, owner), 100);
        f.participant(id, companion);
        var august = builder.build(owner, MONTH.minusMonths(1), GENERATED.withNano(123456789)).orElseThrow();
        assertThat(august.generatedAt().getNano()).isEqualTo(123456000);
        assertThat(store.store(august)).isTrue();
        jdbc.update("UPDATE users SET name = '이름 변경', public_id = 'changed_id' WHERE id = ?", companion);
        jdbc.update("UPDATE settlement_shares SET amount = 200 WHERE id = ?", share);
        jdbc.update("UPDATE settlements SET deleted_at = ? WHERE id = ?", GENERATED, id);
        assertThat(snapshots.find(owner, MONTH.minusMonths(1))).contains(august);
        assertThat(store.store(august)).isFalse();
        UUID sept = completed(f, owner, GENERATED.minusDays(2));
        f.share(sept, f.participant(sept, owner), 200);
        var september = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(september.comparison().status().name()).isEqualTo("INCREASED");
        assertThat(september.comparison().previousOwnAmount()).isEqualTo(100);
        assertThat(builder.build(owner, MONTH.plusMonths(1), GENERATED)).isEmpty();
        assertThat(store.store(september)).isTrue();
        jdbc.update("UPDATE users SET active = false WHERE id = ?", owner);
        assertThat(store.store(september)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void revalidatesSelectedZeroFromFlooringOrDiscount(boolean discounted) {
        var f = new SettlementUnpaidFixtures(jdbc);
        Long owner = f.user(), other = f.user();
        UUID id = f.settlement(owner, SettlementStatus.COMPLETED, SettlementAllocationType.ITEM_ALLOCATION);
        jdbc.update("UPDATE settlements SET updated_at = ?, total_amount = ? WHERE id = ?",
                GENERATED.minusDays(2), discounted ? 1 : 2, id);
        UUID own = f.participant(id, owner), companion = f.participant(id, other);
        f.submitZero(id, own); f.submitZero(id, companion);
        UUID item = item(id, discounted ? 1000 : 3, discounted ? 1000 : 2, discounted ? 999 : 0);
        select(id, item, own, "ONE", 1);
        select(id, item, companion, "ONE", discounted ? 999 : 2);
        var recap = builder.build(owner, MONTH, GENERATED).orElseThrow();
        assertThat(recap.summary().totalOwnAmount()).isZero();
        assertThat(recap.companion().completedSettlementCount()).isOne();
        assertThat(store.store(recap)).isTrue();
        jdbc.update("DELETE FROM monthly_recaps WHERE owner_user_id = ?", owner);
        jdbc.update("UPDATE settlement_items SET amount = 3000 WHERE id = ?", item);
        assertThatThrownBy(() -> builder.build(owner, MONTH, GENERATED)).isInstanceOf(IllegalStateException.class);
    }

    private UUID item(UUID settlement, int quantity, long amount, long receiptDiscount) {
        UUID expense = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_expenses(id, settlement_id, source, description, amount, display_order,
                    created_at, updated_at, discount) VALUES (?, ?, 'MANUAL', '리캡', ?, 0, ?, ?, ?)
                """, expense, settlement, Math.max(amount, 1), GENERATED.minusDays(2), GENERATED.minusDays(2), receiptDiscount);
        jdbc.update("""
                INSERT INTO settlement_items(id, settlement_id, expense_id, name, quantity, amount, display_order,
                    created_at, updated_at) VALUES (?, ?, ?, '리캡', ?, ?, 0, ?, ?)
                """, item, settlement, expense, quantity, amount, GENERATED.minusDays(2), GENERATED.minusDays(2));
        return item;
    }

    private void select(UUID settlement, UUID item, UUID participant, String fraction, int units) {
        jdbc.update("""
                INSERT INTO settlement_item_selections(id, settlement_id, item_id, participant_id, fraction, units,
                    created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), settlement, item, participant, fraction, units, GENERATED.minusDays(2), GENERATED.minusDays(2));
    }

    private UUID completed(SettlementUnpaidFixtures f, Long creator, LocalDateTime at) {
        UUID id = f.settlement(creator, SettlementStatus.COMPLETED, SettlementAllocationType.DUTCH_PAY);
        jdbc.update("UPDATE settlements SET updated_at = ? WHERE id = ?", at, id);
        return id;
    }
}
