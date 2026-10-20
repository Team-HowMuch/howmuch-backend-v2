package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.out.recap.*;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.user.model.ProfileImageUpload;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RecapGenerationRecoveryIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecapGenerationJobStorePort jobs;
    @Autowired private RecapGenerationWorker worker;
    @Autowired private RecapGenerationCompletionService completion;
    @Autowired private BuildMonthlyRecapService builder;
    @Autowired private LoadMonthlyRecapPort snapshots;
    @Autowired private RecapImageStoragePort images;
    @Autowired private RecapImageCleanupStorePort cleanup;
    @Autowired private RecapImageCleanupService cleaner;
    @Autowired private ProfileImageStoragePort profiles;
    @Autowired private PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.amateur.howmuchbackend.adapter.out.persistence.recap.MonthlyRecapPersistenceAdapter snapshotAdapter;
    @Autowired private com.amateur.howmuchbackend.infra.recap.RecapImageProperties imageProperties;
    private SettlementUnpaidFixtures f;
    private final List<String> recapFiles = new ArrayList<>(), profileFiles = new ArrayList<>();
    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    @BeforeEach void setup() { f = new SettlementUnpaidFixtures(jdbc); }
    @AfterEach void teardown() {
        jdbc.execute("TRUNCATE recap_publication_outbox, monthly_recaps, recap_generation_jobs, recap_generation_state, recap_image_cleanup_tasks");
        recapFiles.forEach(images::delete);
        profileFiles.forEach(profiles::delete);
        f.close();
    }

    @Test
    void registrationStartsAtPreviousMonthAndRecoversOnlyFromStoredFirstMonth() {
        Long owner = f.user();
        jobs.registerMissingMonths(MONTH, now(), now());
        assertThat(jdbc.queryForObject("SELECT first_month::text FROM recap_generation_state", String.class)).isEqualTo("2026-09-01");
        assertThat(months(owner)).containsExactly("2026-09-01");
        jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
        jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
        assertThat(months(owner)).containsExactly("2026-09-01", "2026-10-01", "2026-11-01");
        assertThat(jdbc.queryForObject("SELECT first_month::text FROM recap_generation_state", String.class)).isEqualTo("2026-09-01");
    }

    @Test
    void registeredMonthsAreNotReinsertedAndNewOwnersReceiveTheStoredRange() {
        Long owner = f.user();
        jobs.registerMissingMonths(MONTH, now(), now());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            jdbc.execute("""
                    CREATE FUNCTION pg_temp.reject_registered_job() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM recap_generation_jobs
                            WHERE owner_user_id = NEW.owner_user_id AND month_start = NEW.month_start) THEN
                            RAISE EXCEPTION 'Already registered month was attempted again';
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            jdbc.execute("""
                    CREATE TRIGGER verify_registration_cursor BEFORE INSERT ON recap_generation_jobs
                    FOR EACH ROW EXECUTE FUNCTION pg_temp.reject_registered_job()
                    """);
            jobs.registerMissingMonths(MONTH, now(), now());
            jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
            assertThat(months(owner)).containsExactly("2026-09-01", "2026-10-01", "2026-11-01");
            Long newcomer = f.user();
            jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
            assertThat(months(newcomer)).containsExactly("2026-09-01", "2026-10-01", "2026-11-01");
            jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
        });
    }

    @Test
    void registrationRollbackKeepsCursorAndDowntimeMonthsRemainRecoverable() {
        Long owner = f.user();
        jobs.registerMissingMonths(MONTH, now(), now());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
            tx.setRollbackOnly();
        });
        assertThat(months(owner)).containsExactly("2026-09-01");
        assertThat(jdbc.queryForObject("SELECT registered_through_month::text FROM recap_generation_state", String.class))
                .isEqualTo("2026-09-01");
        jobs.registerMissingMonths(MONTH.plusMonths(2), now(), now());
        assertThat(months(owner)).containsExactly("2026-09-01", "2026-10-01", "2026-11-01");
    }

    @Test
    void concurrentWorkersClaimOnceAndDoNotPassAnEarlierUnfinishedMonth() throws Exception {
        Long owner = f.user();
        seed(owner, MONTH, now().minusMinutes(1)); seed(owner, MONTH.plusMonths(1), now().minusMinutes(1));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            List<Future<Optional<com.amateur.howmuchbackend.application.recap.model.RecapGenerationClaim>>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> { gate.await(); return jobs.claimNext(now()); }));
            gate.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS).isPresent()) winners++;
            assertThat(winners).isOne();
        }
        assertThat(jobs.claimNext(now())).isEmpty();
    }

    @Test
    void failedOwnerBlocksOnlyTheirLaterMonthAndDoesNotBlockOtherOwner() {
        Long bad = f.user(), good = f.user();
        completed(bad, false); completed(good, true);
        seed(bad, MONTH, now().minusMinutes(1)); seed(bad, MONTH.plusMonths(1), now().minusMinutes(1));
        seed(good, MONTH, now().minusMinutes(1));
        assertThat(worker.processBatch(10)).isEqualTo(2);
        assertThat(status(bad, MONTH)).isEqualTo("RETRY");
        assertThat(status(bad, MONTH.plusMonths(1))).isEqualTo("PENDING");
        assertThat(status(good, MONTH)).isEqualTo("COMPLETED");
        assertThat(snapshots.find(good, MONTH)).isPresent();
        assertThat(snapshots.find(bad, MONTH)).isEmpty();
    }

    @Test
    void expiredClaimCannotPublishAndItsReservedImageIsDurablyQueued() {
        Long owner = f.user(); completed(owner, true);
        seed(owner, MONTH, now().minusMinutes(20));
        var claim = jobs.claimNext(now().minusMinutes(11)).orElseThrow();
        String reserved = UUID.randomUUID() + ".png";
        assertThat(jobs.reserveImage(claim, reserved, now().minusMinutes(11))).isTrue();
        assertThat(jobs.recoverExpired(now())).isOne();
        var recap = builder.build(owner, MONTH, now()).orElseThrow();
        assertThat(completion.finish(claim, recap)).isFalse();
        assertThat(snapshots.find(owner, MONTH)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_image_cleanup_tasks WHERE file_name = ?", Integer.class, reserved)).isOne();
        worker.processBatch(10);
        assertThat(status(owner, MONTH)).isEqualTo("COMPLETED");
    }

    @Test
    void snapshotInsertAndJobCompletionRollbackTogetherAndRetryKeepsExistingSnapshot() {
        Long owner = f.user(); completed(owner, true); seed(owner, MONTH, now().minusMinutes(1));
        var claim = jobs.claimNext(now()).orElseThrow();
        var recap = builder.build(owner, MONTH, now()).orElseThrow();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(completion.finish(claim, recap)).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(status(owner, MONTH)).isEqualTo("PROCESSING");
        assertThat(snapshots.find(owner, MONTH)).isEmpty();
        assertThat(completion.finish(claim, recap)).isTrue();
        assertThat(snapshots.find(owner, MONTH)).contains(recap);
        jdbc.update("UPDATE recap_generation_jobs SET status='PENDING', attempt_count=0, next_attempt_at=?, completed_at=NULL WHERE id=?", now(), claim.id());
        worker.processBatch(10);
        assertThat(snapshots.find(owner, MONTH)).contains(recap);
    }

    @Test
    void missingProfileRetriesThenCopiesAndKeepsImageAfterOriginalRemoval() {
        Long owner = f.user(), companion = f.user();
        UUID settlement = completed(owner, true); f.participant(settlement, companion);
        String missing = UUID.randomUUID() + ".png";
        jdbc.update("UPDATE users SET avatar_file_name = ? WHERE id = ?", missing, companion);
        seed(owner, MONTH, now().minusMinutes(1));
        worker.processBatch(10);
        assertThat(status(owner, MONTH)).isEqualTo("RETRY");
        assertThat(snapshots.find(owner, MONTH)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_image_cleanup_tasks", Integer.class)).isOne();
        String original = profiles.save(new ProfileImageUpload("image/png", "png", new byte[]{1,2,3}));
        profileFiles.add(original);
        jdbc.update("UPDATE users SET avatar_file_name = ? WHERE id = ?", original, companion);
        jdbc.update("UPDATE recap_generation_jobs SET next_attempt_at = ? WHERE owner_user_id = ?", now(), owner);
        worker.processBatch(10);
        var recap = snapshots.find(owner, MONTH).orElseThrow();
        String copy = recap.companion().avatarFileName(); recapFiles.add(copy);
        assertThat(copy).isNotEqualTo(original);
        profiles.delete(original);
        assertThat(images.load(copy).orElseThrow().content()).containsExactly(1,2,3);
        cleanup.enqueue(copy, now());
        cleaner.cleanBatch(100);
        assertThat(images.load(copy)).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM recap_image_cleanup_tasks WHERE file_name=?", String.class, copy)).isEqualTo("RETRY");
    }

    @Test
    void activeReservationAndStoredCopiesAreProtectedAndInactiveOwnerIsSkipped() {
        Long owner = f.user(); seed(owner, MONTH, now().minusMinutes(1));
        var claim = jobs.claimNext(now()).orElseThrow();
        String file = UUID.randomUUID() + ".png";
        jobs.reserveImage(claim, file, now());
        assertThat(cleanup.isReferenced(file, now())).isTrue();
        jdbc.update("UPDATE users SET active=false WHERE id=?", owner);
        assertThat(completion.finish(claim, null)).isTrue();
        assertThat(status(owner, MONTH)).isEqualTo("SKIPPED");
        assertThat(cleanup.isReferenced(file, now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_image_cleanup_tasks WHERE file_name=?", Integer.class, file)).isOne();
    }

    @Test
    void lateExpiredCopyAndOldOrphansAreDeletedButActiveCopyAndRecentFilesRemain() throws Exception {
        Long owner = f.user(); seed(owner, MONTH, now().minusMinutes(20));
        String original = profiles.save(new ProfileImageUpload("image/png", "png", new byte[]{1,2,3}));
        profileFiles.add(original);
        var stale = jobs.claimNext(now().minusMinutes(11)).orElseThrow();
        String late = UUID.randomUUID() + ".png"; recapFiles.add(late);
        jobs.reserveImage(stale, late, now().minusMinutes(11));
        jobs.recoverExpired(now());
        images.copyProfile(original, late);
        var current = jobs.claimNext(now()).orElseThrow();
        String active = UUID.randomUUID() + ".png"; recapFiles.add(active);
        jobs.reserveImage(current, active, now()); images.copyProfile(original, active);
        var directory = java.nio.file.Path.of(imageProperties.storageDir());
        var oldTime = java.nio.file.attribute.FileTime.from(now().minusDays(2).toInstant(ZoneOffset.UTC));
        java.nio.file.Files.setLastModifiedTime(directory.resolve(active), oldTime);
        String orphan = UUID.randomUUID() + ".png"; recapFiles.add(orphan);
        java.nio.file.Files.write(directory.resolve(orphan + ".tmp"), new byte[]{1});
        java.nio.file.Files.setLastModifiedTime(directory.resolve(orphan + ".tmp"), oldTime);
        String recent = UUID.randomUUID() + ".png"; recapFiles.add(recent);
        images.copyProfile(original, recent);
        cleaner.cleanBatch(100);
        assertThat(images.load(late)).isEmpty();
        assertThat(java.nio.file.Files.exists(directory.resolve(orphan + ".tmp"))).isFalse();
        assertThat(images.load(active)).isPresent();
        assertThat(images.load(recent)).isPresent();
    }

    @Test
    void expiryAfterSnapshotInsertRollsBackTheWholePublication() {
        Long owner = f.user(); completed(owner, true); seed(owner, MONTH, now().minusMinutes(1));
        var claim = jobs.claimNext(now()).orElseThrow();
        var recap = builder.build(owner, MONTH, now()).orElseThrow();
        org.mockito.Mockito.doAnswer(invocation -> {
            var stored = invocation.callRealMethod();
            jdbc.update("UPDATE recap_generation_jobs SET processing_started_at=?, lease_until=? WHERE id=?",
                    now().minusMinutes(11), now().minusMinutes(1), claim.id());
            return stored;
        }).when(snapshotAdapter).insertIfAbsent(recap);
        assertThatThrownBy(() -> completion.finish(claim, recap)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired before publication");
        assertThat(snapshots.find(owner, MONTH)).isEmpty();
        assertThat(status(owner, MONTH)).isEqualTo("PROCESSING");
    }

    @Test
    void firstDayRestartRegistersButDoesNotClaimBefore0010Kst() {
        Long owner = f.user();
        var midnight = LocalDateTime.of(2026, 9, 30, 15, 0);
        jobs.registerMissingMonths(MONTH, midnight, midnight.plusMinutes(10));
        assertThat(jobs.claimNext(midnight.plusMinutes(9))).isEmpty();
        assertThat(jobs.claimNext(midnight.plusMinutes(10))).isPresent();
    }

    private UUID completed(Long owner, boolean share) {
        UUID id = f.settlement(owner, SettlementStatus.COMPLETED, SettlementAllocationType.DUTCH_PAY);
        jdbc.update("UPDATE settlements SET updated_at='2026-09-15 00:00:00' WHERE id=?", id);
        UUID participant = f.participant(id, owner);
        if (share) f.share(id, participant, 10_000);
        return id;
    }
    private void seed(Long owner, YearMonth month, LocalDateTime due) {
        jdbc.update("""
                INSERT INTO recap_generation_jobs(id, owner_user_id, month_start, status, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), owner, java.sql.Date.valueOf(month.atDay(1)), due, due, due);
    }
    private String status(Long owner, YearMonth month) {
        return jdbc.queryForObject("SELECT status FROM recap_generation_jobs WHERE owner_user_id=? AND month_start=?",
                String.class, owner, java.sql.Date.valueOf(month.atDay(1)));
    }
    private List<String> months(Long owner) {
        return jdbc.queryForList("SELECT month_start::text FROM recap_generation_jobs WHERE owner_user_id=? ORDER BY month_start",
                String.class, owner);
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
