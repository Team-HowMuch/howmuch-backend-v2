package com.amateur.howmuchbackend.adapter.in.web.recap;

import com.amateur.howmuchbackend.application.port.out.auth.*;
import com.amateur.howmuchbackend.application.port.out.recap.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.recap.service.*;
import com.amateur.howmuchbackend.application.user.model.ProfileImageUpload;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false",
        "howmuch.recap.generation.enabled=false"
})
class RecapHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthSessionPort sessions;
    @Autowired AuthTokenPort tokens;
    @Autowired LoadUserPort users;
    @Autowired LoadMonthlyRecapPort snapshots;
    @Autowired RecapGenerationJobStorePort jobs;
    @Autowired RecapGenerationWorker worker;
    @Autowired BuildMonthlyRecapService builder;
    @Autowired RecapGenerationCompletionService completion;
    @Autowired RecapSnapshotService snapshotService;
    @Autowired DeleteOwnRecapsPort deletion;
    @Autowired RecapImageCleanupService cleaner;
    @Autowired ProfileImageStoragePort profiles;
    @Autowired RecapImageStoragePort images;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private SettlementUnpaidFixtures f;
    private Long owner, other;
    private String ownerToken, otherToken, ownerProfile, otherProfile;
    private final List<String> copiedFiles = new ArrayList<>();
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final String ROOT = "/api/v2/recaps";

    @BeforeEach void setup() {
        f = new SettlementUnpaidFixtures(jdbc); owner = f.user(); other = f.user();
        jdbc.update("UPDATE users SET name='본인 원래 이름', public_id='owner_original', pin_hash=? WHERE id=?", encoder.encode("357924"), owner);
        jdbc.update("UPDATE users SET name='상대 원래 이름', public_id='other_original', pin_hash=? WHERE id=?", encoder.encode("357924"), other);
        ownerProfile = profiles.save(new ProfileImageUpload("image/png", "png", new byte[]{1,2,3}));
        otherProfile = profiles.save(new ProfileImageUpload("image/png", "png", new byte[]{4,5,6}));
        jdbc.update("UPDATE users SET avatar_file_name=? WHERE id=?", ownerProfile, owner);
        jdbc.update("UPDATE users SET avatar_file_name=? WHERE id=?", otherProfile, other);
        ownerToken = token(owner); otherToken = token(other);
        UUID together = completed(owner, MONTH);
        f.share(together, f.participant(together, owner), 10_000);
        f.share(together, f.participant(together, other), 30_000);
        UUID alone = completed(other, MONTH);
        f.share(alone, f.participant(alone, other), 20_000);
        seed(owner, MONTH); seed(other, MONTH); worker.processBatch(100);
        copiedFiles.add(snapshots.find(owner, MONTH).orElseThrow().companion().avatarFileName());
        copiedFiles.add(snapshots.find(other, MONTH).orElseThrow().companion().avatarFileName());
    }
    @AfterEach void teardown() {
        sessions.deleteByUserId(owner); sessions.deleteByUserId(other);
        jdbc.execute("TRUNCATE monthly_recaps, recap_generation_jobs, recap_generation_state, recap_image_cleanup_tasks");
        copiedFiles.forEach(images::delete); profiles.delete(ownerProfile); profiles.delete(otherProfile);
        f.close();
    }

    @Test void allFourApisRequireValidJwtAndRedisSession() throws Exception {
        for (String path : List.of(ROOT, ROOT + "/2026-09", ROOT + "/2026-09/share", ROOT + "/2026-09/companion/avatar")) {
            assertThat(get(path, null).statusCode()).isEqualTo(401);
            assertThat(get(path, "not-a-jwt").statusCode()).isEqualTo(401);
        }
        sessions.deleteByUserId(owner);
        for (String path : List.of(ROOT, ROOT + "/2026-09", ROOT + "/2026-09/share", ROOT + "/2026-09/companion/avatar")) {
            assertThat(get(path, ownerToken).statusCode()).isEqualTo(401);
        }
    }

    @Test void everyApiSeparatesOwnersAndShareHidesRawNamesIdsAndFileNames() throws Exception {
        assertThat(data(get(ROOT, ownerToken)).path("months").toString()).isEqualTo("[\"2026-09\"]");
        var mine = data(get(ROOT + "/2026-09?userId=" + other, ownerToken));
        var theirs = data(get(ROOT + "/2026-09", otherToken));
        assertThat(mine.path("totalOwnAmount").longValue()).isEqualTo(10_000);
        assertThat(theirs.path("totalOwnAmount").longValue()).isEqualTo(50_000);
        assertThat(mine.path("completedSettlementCount").intValue()).isOne();
        assertThat(theirs.path("completedSettlementCount").intValue()).isEqualTo(2);
        var shared = get(ROOT + "/2026-09/share", ownerToken);
        var safe = data(shared);
        assertThat(safe.path("companion").path("displayName").stringValue()).isEqualTo("other_original");
        assertThat(safe.has("ownerName")).isFalse();
        assertThat(shared.body()).doesNotContain("본인 원래 이름", "상대 원래 이름", "owner_original",
                "identityKey", "ownerId", "linkedUserId", "avatarFileName", ownerProfile, otherProfile);
        for (String copied : copiedFiles) assertThat(shared.body()).doesNotContain(copied);
        assertThat(data(get(ROOT + "/2026-09/share", otherToken)).path("companion").path("displayName").stringValue())
                .isEqualTo("owner_original");
        var myImage = bytes(ROOT + "/2026-09/companion/avatar", ownerToken);
        var theirImage = bytes(ROOT + "/2026-09/companion/avatar", otherToken);
        assertThat(myImage.body()).containsExactly(4,5,6);
        assertThat(theirImage.body()).containsExactly(1,2,3);
        assertThat(myImage.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(myImage.headers().firstValue("Content-Type")).contains("image/png");
    }

    @Test void fixedNamesPublicIdsAndCopiedPhotosSurviveOriginalChangesAndDeletion() throws Exception {
        var before = get(ROOT + "/2026-09", ownerToken).body();
        var shareBefore = get(ROOT + "/2026-09/share", ownerToken).body();
        jdbc.update("UPDATE users SET name='변경 이름', public_id='changed_public', avatar_file_name=NULL WHERE id=?", other);
        jdbc.update("UPDATE settlement_shares SET amount=99999 WHERE settlement_id IN (SELECT id FROM settlements WHERE creator_id=?)", owner);
        jdbc.update("UPDATE settlements SET deleted_at=? WHERE creator_id=?", now(), owner);
        profiles.delete(otherProfile);
        assertThat(get(ROOT + "/2026-09", ownerToken).body()).isEqualTo(before);
        assertThat(get(ROOT + "/2026-09/share", ownerToken).body()).isEqualTo(shareBefore);
        assertThat(bytes(ROOT + "/2026-09/companion/avatar", ownerToken).body()).containsExactly(4,5,6);
    }

    @Test void absentMonthsReturn404AndListStaysSortedAndPrivate() throws Exception {
        UUID august = completed(owner, MONTH.minusMonths(1));
        f.share(august, f.participant(august, owner), 100);
        snapshotService.store(builder.build(owner, MONTH.minusMonths(1), now()).orElseThrow());
        assertThat(data(get(ROOT, ownerToken)).path("months").toString()).isEqualTo("[\"2026-09\",\"2026-08\"]");
        assertThat(data(get(ROOT, otherToken)).path("months").toString()).isEqualTo("[\"2026-09\"]");
        for (String month : List.of("0001-01", "2026-10", "9999-12")) {
            for (String suffix : List.of("", "/share", "/companion/avatar")) {
                var response = get(ROOT + "/" + month + suffix, ownerToken);
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(json.readTree(response.body()).path("error").path("code").stringValue()).isEqualTo("RECAP_NOT_FOUND");
            }
        }
        jdbc.update("DELETE FROM monthly_recaps WHERE owner_user_id=?", owner);
        assertThat(data(get(ROOT, ownerToken)).path("months").toString()).isEqualTo("[]");
    }

    @Test void noCompanionAndGuestMaskAndNoPhotoHaveExplicitContracts() throws Exception {
        UUID august = completed(owner, MONTH.minusMonths(1));
        f.share(august, f.participant(august, owner), 100);
        var alone = builder.build(owner, MONTH.minusMonths(1), now()).orElseThrow(); snapshotService.store(alone);
        assertThat(data(get(ROOT + "/2026-08", ownerToken)).path("companion").isNull()).isTrue();
        assertImageMissing("2026-08");
        UUID july = completed(owner, MONTH.minusMonths(2));
        f.share(july, f.participant(july, owner), 100);
        UUID guest = f.participant(july, null); jdbc.update("UPDATE settlement_participants SET display_name='김가나' WHERE id=?", guest);
        snapshotService.store(builder.build(owner, MONTH.minusMonths(2), now()).orElseThrow());
        assertThat(data(get(ROOT + "/2026-07", ownerToken)).path("companion").path("displayName").stringValue()).isEqualTo("김가나");
        var shared = get(ROOT + "/2026-07/share", ownerToken);
        assertThat(data(shared).path("companion").path("displayName").stringValue()).isEqualTo("김*나");
        assertThat(shared.body()).doesNotContain("김가나", "본인 원래 이름");
        assertThat(data(shared).path("companion").path("avatarUrl").isNull()).isTrue();
        assertImageMissing("2026-07");
    }

    @Test void withdrawalRemovesOnlyOwnRecapsJobsAndFilesWhilePeerSnapshotRemainsFixed() throws Exception {
        var peerBefore = get(ROOT + "/2026-09", ownerToken).body();
        String peerCopy = copiedFiles.getFirst(), ownCopy = copiedFiles.getLast();
        seed(other, MONTH.plusMonths(1));
        var active = jobs.claimNext(now()).orElseThrow();
        String reserved = UUID.randomUUID() + ".png"; copiedFiles.add(reserved);
        jobs.reserveImage(active, reserved, now()); images.copyProfile(ownerProfile, reserved);
        var response = delete(otherToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(users.findById(other).orElseThrow().active()).isFalse();
        assertThat(snapshots.find(other, MONTH)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_generation_jobs WHERE owner_user_id=?", Integer.class, other)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_image_cleanup_tasks", Integer.class)).isEqualTo(2);
        assertThat(get(ROOT, otherToken).statusCode()).isEqualTo(401);
        assertThat(get(ROOT + "/2026-09", ownerToken).body()).isEqualTo(peerBefore);
        cleaner.cleanBatch(100);
        assertThat(images.load(ownCopy)).isEmpty(); assertThat(images.load(reserved)).isEmpty();
        assertThat(images.load(peerCopy)).isPresent();
        assertThat(bytes(ROOT + "/2026-09/companion/avatar", ownerToken).body()).containsExactly(4,5,6);
        UUID august = completed(owner, MONTH.minusMonths(1));
        f.share(august, f.participant(august, owner), 100); f.participant(august, other);
        snapshotService.store(builder.build(owner, MONTH.minusMonths(1), now()).orElseThrow());
        var withdrawn = get(ROOT + "/2026-08/share", ownerToken);
        assertThat(data(withdrawn).path("companion").path("kind").stringValue()).isEqualTo("WITHDRAWN_MEMBER");
        assertThat(data(withdrawn).path("companion").path("displayName").stringValue()).isEqualTo("탈퇴한 회원");
        assertThat(withdrawn.body()).doesNotContain("del_", "상대 원래 이름", "other_original");
        assertImageMissing("2026-08");
    }

    @Test void deletionQueueAndSnapshotsRollbackTogether() {
        var before = snapshots.find(owner, MONTH).orElseThrow();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("UPDATE users SET active=false WHERE id=?", owner);
            deletion.deleteOwnedRecaps(owner, now());
            assertThat(snapshots.find(owner, MONTH)).isEmpty();
            tx.setRollbackOnly();
        });
        assertThat(snapshots.find(owner, MONTH)).contains(before);
        assertThat(users.findById(owner).orElseThrow().active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recap_image_cleanup_tasks", Integer.class)).isZero();
    }

    @Test void withdrawalBeforePublicationFencesTheWaitingWorkerWithoutDeadlock() throws Exception {
        UUID august = completed(owner, MONTH.minusMonths(1)); f.share(august, f.participant(august, owner), 100);
        seed(owner, MONTH.minusMonths(1)); var claim = jobs.claimNext(now()).orElseThrow();
        var recap = builder.build(owner, MONTH.minusMonths(1), now()).orElseThrow();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deleting = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.update("UPDATE users SET active=false WHERE id=?", owner);
                deletion.deleteOwnedRecaps(owner, now()); locked.countDown(); await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var publishing = pool.submit(() -> completion.finish(claim, recap));
            try { assertThatThrownBy(() -> publishing.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            deleting.get(10, TimeUnit.SECONDS);
            assertThat(publishing.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(snapshots.find(owner, MONTH.minusMonths(1))).isEmpty();
            assertThat(snapshots.find(other, MONTH)).isPresent();
        }
    }

    @Test void publicationBeforeWithdrawalIsDeletedWithinTheWithdrawalTransaction() throws Exception {
        UUID august = completed(owner, MONTH.minusMonths(1)); f.share(august, f.participant(august, owner), 100);
        seed(owner, MONTH.minusMonths(1)); var claim = jobs.claimNext(now()).orElseThrow();
        var recap = builder.build(owner, MONTH.minusMonths(1), now()).orElseThrow();
        var published = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var publishing = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertThat(completion.finish(claim, recap)).isTrue(); published.countDown(); await(release);
            }));
            assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
            var deleting = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.update("UPDATE users SET active=false WHERE id=?", owner); deletion.deleteOwnedRecaps(owner, now());
            }));
            try { assertThatThrownBy(() -> deleting.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            publishing.get(10, TimeUnit.SECONDS); deleting.get(10, TimeUnit.SECONDS);
            assertThat(snapshots.find(owner, MONTH.minusMonths(1))).isEmpty();
            assertThat(snapshots.find(owner, MONTH)).isEmpty();
            assertThat(snapshots.find(other, MONTH)).isPresent();
        }
    }

    @Test void swaggerDocumentsAuthenticationSchemasAndBinaryResponse() throws Exception {
        var response = get("/v3/api-docs", null); assertThat(response.statusCode()).isEqualTo(200);
        var doc = json.readTree(response.body());
        for (String path : List.of(ROOT, ROOT + "/{yearMonth}", ROOT + "/{yearMonth}/share", ROOT + "/{yearMonth}/companion/avatar")) {
            var operation = doc.path("paths").path(path).path("get");
            assertThat(operation.path("security").toString()).contains("bearerAuth");
            assertThat(operation.path("responses").has("200")).isTrue();
            assertThat(operation.path("responses").has("401")).isTrue();
        }
        var binary = doc.path("paths").path(ROOT + "/{yearMonth}/companion/avatar").path("get").path("responses").path("200").path("content");
        assertThat(binary.has("image/png")).isTrue(); assertThat(binary.has("application/json")).isFalse();
        var shareSchema = doc.path("components").path("schemas").path("SharedMonthlyRecapWebResponse").path("properties");
        assertThat(shareSchema.has("ownerName")).isFalse();
        assertThat(shareSchema.path("totalOwnAmount").path("format").stringValue()).isEqualTo("int64");
        assertThat(shareSchema.path("completedSettlementCount").path("format").stringValue()).isEqualTo("int32");
    }

    @Test
    void detailCards_workerPublication_returnsFixedPersonalCardsAndPrivateTitle() throws Exception {
        UUID settlement = completed(owner, MONTH.minusMonths(1));
        LocalDateTime completedAt = MONTH.minusMonths(1).atDay(15).atStartOfDay();
        f.share(settlement, f.participant(settlement, owner), 1000);
        f.share(settlement, f.participant(settlement, other), 9000);
        jdbc.update("UPDATE settlements SET title='고정할 정산 제목' WHERE id=?", settlement);
        jdbc.update("""
                INSERT INTO settlement_categories(settlement_id,status,category_code,source,classified_at,created_at,updated_at)
                VALUES (?,'CLASSIFIED','FOOD','RULE',?,?,?)
                """, settlement, completedAt, completedAt, completedAt);
        jdbc.update("""
                INSERT INTO settlement_user_categories(settlement_id,user_id,category_code,created_at,updated_at)
                VALUES (?,?,'CAFE_SNACK',?,?)
                """, settlement, owner, completedAt, completedAt);
        jdbc.update("""
                INSERT INTO settlement_links(id,settlement_id,token_hash,status,expires_at,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID(), settlement, UUID.randomUUID().toString().replace("-", "").repeat(2), now().plusDays(1),
                completedAt.minusSeconds(65), completedAt.minusSeconds(65));
        seed(owner, MONTH.minusMonths(1));
        worker.processBatch(100);
        copiedFiles.add(snapshots.find(owner, MONTH.minusMonths(1)).orElseThrow().companion().avatarFileName());

        var response = get(ROOT + "/2026-08", ownerToken);
        var detail = data(response);
        var category = detail.path("categories").get(0);
        assertThat(category.path("rank").intValue()).isOne();
        assertThat(category.path("totalOwnAmount").longValue()).isEqualTo(1000);
        assertThat(category.path("category").path("code").stringValue()).isEqualTo("CAFE_SNACK");
        assertThat(category.path("category").path("name").stringValue()).isEqualTo("카페 / 간식");
        assertThat(category.path("category").path("icon").stringValue()).isEqualTo("cafe-snack");
        var record = detail.path("record");
        assertThat(record.path("busiestWeekday").path("weekday").stringValue()).isEqualTo("SATURDAY");
        assertThat(record.path("busiestWeekday").path("count").intValue()).isOne();
        assertThat(record.path("frequentCategory").path("count").intValue()).isOne();
        assertThat(record.path("frequentCategory").path("category")).isEqualTo(category.path("category"));
        assertThat(record.path("largestSettlement").path("totalOwnAmount").longValue()).isEqualTo(1000);
        assertThat(record.path("largestSettlement").path("category")).isEqualTo(category.path("category"));
        assertThat(record.path("fastestSettlement").path("durationMinutes").longValue()).isOne();
        assertThat(record.path("fastestSettlement").path("title").stringValue()).isEqualTo("고정할 정산 제목");
        assertThat(detail.has("benefits")).isTrue();
        assertThat(detail.path("benefits").isNull()).isTrue();
        assertThat(get(ROOT + "/2026-08", otherToken).statusCode()).isEqualTo(404);
        var shared = data(get(ROOT + "/2026-08/share", ownerToken));
        assertThat(shared.has("categories") || shared.has("record") || shared.has("benefits")).isFalse();
        assertThat(shared.toString()).doesNotContain("고정할 정산 제목");

        jdbc.update("UPDATE settlement_user_categories SET category_code='SHOPPING' WHERE settlement_id=? AND user_id=?", settlement, owner);
        jdbc.update("UPDATE settlement_shares SET amount=90000 WHERE settlement_id=?", settlement);
        jdbc.update("UPDATE settlements SET title='바뀐 제목', deleted_at=? WHERE id=?", now(), settlement);
        assertThat(get(ROOT + "/2026-08", ownerToken).body()).isEqualTo(response.body());
    }

    @Test
    void detailCards_legacySnapshot_returnsExplicitNullFields() throws Exception {
        jdbc.update("""
                INSERT INTO monthly_recaps(id,owner_user_id,month_start,owner_name,completed_settlement_count,
                    total_own_amount,comparison_status,generated_at)
                VALUES (?,?,'2026-07-01','기존 회원',1,1000,'NO_PREVIOUS',?)
                """, UUID.randomUUID(), owner, now());
        var detail = data(get(ROOT + "/2026-07", ownerToken));
        for (String field : List.of("categories", "record", "benefits")) {
            assertThat(detail.has(field)).as(field).isTrue();
            assertThat(detail.path(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void openApi_detailCards_documentsNullableArraysAndNestedRecords() throws Exception {
        var doc = json.readTree(get("/v3/api-docs", null).body());
        var schemas = doc.path("components").path("schemas");
        var detail = schemas.path("MonthlyRecapWebResponse");
        assertThat(detail.path("required").toString()).contains("categories", "record", "benefits");
        assertNullableReference(schemas, "MonthlyRecapWebResponse", "record", "RecapRecordWebResponse");
        for (String field : List.of("categories", "benefits")) {
            var property = detail.path("properties").path(field);
            assertThat(property.path("nullable").asBoolean(false) || property.path("type").toString().contains("null"))
                    .as("nullable array %s: %s", field, property).isTrue();
            assertThat(property.path("type").toString()).contains("array");
            assertThat(property.path("maxItems").intValue()).isEqualTo(field.equals("categories") ? 3 : 2);
            assertThat(property.path("items").path("$ref").stringValue()).endsWith(
                    field.equals("categories") ? "/RecapCategoryTotalWebResponse" : "/RecapBenefitWebResponse");
            assertThat(property.path("items").path("type").toString()).doesNotContain("null");
        }
        var targets = Map.of("busiestWeekday", "RecapBusiestWeekdayWebResponse",
                "frequentCategory", "RecapFrequentCategoryWebResponse", "largestSettlement", "RecapLargestSettlementWebResponse",
                "fastestSettlement", "RecapFastestSettlementWebResponse");
        targets.forEach((field, target) -> {
            assertNullableReference(schemas, "RecapRecordWebResponse", field, target);
            assertThat(schemas.path("RecapRecordWebResponse").path("required").toString()).contains(field);
        });
        assertNullableReference(schemas, "RecapLargestSettlementWebResponse", "category", "RecapCategoryWebResponse");
        assertThat(schemas.path("RecapBusiestWeekdayWebResponse").path("properties").path("weekday").path("enum").size()).isEqualTo(7);
        assertThat(schemas.path("RecapFastestSettlementWebResponse").path("properties").path("durationMinutes").path("format").stringValue()).isEqualTo("int64");
        assertThat(schemas.path("RecapCategoryTotalWebResponse").path("properties").path("totalOwnAmount").path("format").stringValue()).isEqualTo("int64");
        assertThat(schemas.path("RecapCategoryWebResponse").path("required").toString()).contains("code", "name", "icon");
        for (String schema : List.of("RecapCategoryTotalWebResponse", "RecapCategoryWebResponse", "RecapBenefitWebResponse",
                "RecapRecordWebResponse", "RecapBusiestWeekdayWebResponse", "RecapFrequentCategoryWebResponse",
                "RecapLargestSettlementWebResponse", "RecapFastestSettlementWebResponse")) {
            assertThat(schemas.path(schema).path("type").toString()).as(schema).contains("object").doesNotContain("null");
        }
        var shareProperties = schemas.path("SharedMonthlyRecapWebResponse").path("properties");
        assertThat(shareProperties.has("categories") || shareProperties.has("record") || shareProperties.has("benefits")).isFalse();
    }

    private void assertNullableReference(JsonNode schemas, String schema, String field, String target) {
        var property = schemas.path(schema).path("properties").path(field);
        boolean nullable = property.path("nullable").asBoolean(false)
                && property.toString().contains("#/components/schemas/" + target + "\"");
        for (String keyword : List.of("anyOf", "oneOf")) {
            boolean reference = false, nullType = false;
            for (var branch : property.path(keyword)) {
                reference |= branch.path("$ref").asString("").equals("#/components/schemas/" + target);
                nullType |= branch.path("type").toString().contains("null");
            }
            nullable |= reference && nullType;
        }
        assertThat(nullable).as("%s.%s nullable reference: %s", schema, field, property).isTrue();
    }

    private void assertImageMissing(String month) throws Exception {
        var response = get(ROOT + "/" + month + "/companion/avatar", ownerToken);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body()).path("error").path("code").stringValue()).isEqualTo("RECAP_IMAGE_NOT_FOUND");
    }
    private UUID completed(Long creator, YearMonth month) {
        UUID id = f.settlement(creator, SettlementStatus.COMPLETED, SettlementAllocationType.DUTCH_PAY);
        jdbc.update("UPDATE settlements SET updated_at=? WHERE id=?", month.atDay(15).atStartOfDay(), id); return id;
    }
    private void seed(Long id, YearMonth month) {
        jdbc.update("""
                INSERT INTO recap_generation_jobs(id,owner_user_id,month_start,status,next_attempt_at,created_at,updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), id, java.sql.Date.valueOf(month.atDay(1)), now(), now(), now());
    }
    private String token(Long id) {
        String sid = UUID.randomUUID().toString();
        sessions.save(new AuthSession(id, sid, "synthetic-unused", now().plusMinutes(10)));
        return tokens.createAccessToken(users.findById(id).orElseThrow(), sid);
    }
    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15)).GET();
        if (token != null) r.header("Authorization", "Bearer " + token);
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<byte[]> bytes(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<String> delete(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/user/me"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15)).method("DELETE", HttpRequest.BodyPublishers.ofString("{\"pin\":\"357924\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    private void await(CountDownLatch gate) {
        try { if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
