package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.notification.*;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.settlement.service.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@AutoConfigureMockMvc
class NotificationInboxIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0);
    @Autowired NotificationRecorder recorder;
    @Autowired LoadNotificationPort notifications;
    @Autowired MarkNotificationReadPort reads;
    @Autowired SettlementAutomaticReminderTargetPort targets;
    @Autowired SettlementAutomaticReminderService automaticReminders;
    @Autowired SettlementReminderWriter writer;
    @Autowired LoadSettlementSharePort shareReader;
    @Autowired SaveSettlementSharePort shareStore;
    @Autowired SettlementItemAllocationStorePort itemAllocations;
    @Autowired SettlementNotificationOutboxWorker worker;
    @Autowired SettlementReminderCooldownPort cooldown;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentActorPort actor;
    @MockitoBean Clock clock;
    @MockitoSpyBean SettlementNotificationOutboxPort outbox;
    Long creator, recipient, account;
    UUID settlement, participant, share;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        creator = insertUser(); recipient = insertUser();
        account = -creator;
        settlement = UUID.randomUUID(); participant = UUID.randomUUID(); share = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_records(id,user_id,organization,type,name,masked_number,linked_at)
                VALUES (?,?,'0020','BANK','테스트','***',?)
                """, account, creator, NOW.minusDays(2));
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,created_at,updated_at)
                VALUES (?,?,'알림 검증',1000,'DUTCH_PAY','AWAITING_DEPOSIT',?,?)
                """, settlement, creator, NOW.minusDays(2), NOW);
        jdbc.update("""
                INSERT INTO settlement_receiving_account_snapshots
                (settlement_id,source_account_id,organization,account_number_ciphertext,account_holder,created_at)
                VALUES (?,?,'0020','test-ciphertext','예금주',?)
                """, settlement, account, NOW.minusDays(2));
        jdbc.update("""
                INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,display_name,display_order,created_at,updated_at)
                VALUES (?,?,'REGISTERED_USER',?,'참여자',0,?,?)
                """, participant, settlement, recipient, NOW.minusDays(2), NOW);
        shareStore.saveAll(List.of(new com.amateur.howmuchbackend.domain.settlement.SettlementShare(
                share, settlement, participant, 1000, NOW.minusHours(25))));
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(recipient));
    }

    @AfterEach
    void cleanUp() {
        reset(outbox);
        redis.delete("settlement:reminder:" + settlement + ":" + participant);
        jdbc.update("DELETE FROM settlement_deposits WHERE settlement_id=?", settlement);
        jdbc.update("DELETE FROM settlements WHERE id=?", settlement);
        jdbc.update("DELETE FROM account_transactions WHERE account_id=?", account);
        jdbc.update("DELETE FROM account_records WHERE id=?", account);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", creator, recipient);
    }

    @Test
    void notificationRecord_sameRequest_concurrently_createsOneOutbox() throws Exception {
        var first = request(); var second = request();
        var results = concurrent(() -> recorder.record(first), () -> recorder.record(second));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(notifications.countUnread(recipient)).isOne();
        assertThat(countOutbox()).isOne();
    }

    @Test
    void notificationCursor_equalTimestamp_usesUuidOrder() throws Exception {
        var one = request(); recorder.record(one);
        var two = NotificationMessages.reminder(settlement, recipient, SettlementAllocationType.ITEM_ALLOCATION,
                "요청 당시 이름", "manual-test", "정산해 주세요", NOW.minusHours(25));
        recorder.record(two);
        // 같은 시각이면 PostgreSQL UUID 순서가 페이지 cursor의 최종 정렬 기준을 결정한다.
        var ordered = notifications.findPage(recipient, null, null, 10);
        var response = mvc.perform(get("/api/v2/notifications").param("size", "1").with(user("member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[0].readAt").isEmpty()).andReturn();
        var data = json.readTree(response.getResponse().getContentAsString()).get("data");
        assertThat(data.get("items").get(0).get("id").asText()).isEqualTo(ordered.getFirst().id().toString());
        mvc.perform(get("/api/v2/notifications").param("size", "1")
                        .param("cursor", data.get("nextCursor").asText()).with(user("member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value(ordered.getLast().id().toString()));
        mvc.perform(get("/api/v2/notifications/unread-count").with(user("member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(2));
        mvc.perform(patch("/api/v2/notifications/{id}/read", one.id()).with(user("member")))
                .andExpect(status().isOk());
        assertThat(notifications.countUnread(recipient)).isOne();
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(creator));
        mvc.perform(get("/api/v2/notifications").with(user("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(patch("/api/v2/notifications/{id}/read", one.id()).with(user("owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(patch("/api/v2/notifications/{id}/read", UUID.randomUUID()).with(user("owner")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?", String.class, settlement))
                .isEqualTo("AWAITING_DEPOSIT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_deposits WHERE settlement_id=?", Long.class, settlement)).isZero();
    }

    @Test
    void notificationEndpoint_anonymousOrInvalidQuery_returnsErrors() throws Exception {
        mvc.perform(get("/api/v2/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v2/notifications/{id}/read", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/notifications").param("size", "51").with(user("member"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/notifications").param("cursor", "invalid").with(user("member"))).andExpect(status().isBadRequest());
    }

    @Test
    void notificationRead_concurrentRequests_keepsFirstTimestamp() throws Exception {
        var n = request(); recorder.record(n);
        var results = concurrent(() -> reads.markRead(n.id(), recipient, NOW),
                () -> reads.markRead(n.id(), recipient, NOW.plusSeconds(1)));
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(reads.markRead(n.id(), recipient, NOW.plusDays(1))).isEqualTo(results.getFirst());
        assertThat(notifications.countUnread(recipient)).isZero();
    }

    @Test
    void settlementDeleted_notificationMessageKeptActionDisabled() throws Exception {
        var n = request(); recorder.record(n);
        jdbc.update("DELETE FROM settlements WHERE id=?", settlement);
        mvc.perform(get("/api/v2/notifications").with(user("member")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].settlementId").isEmpty())
                .andExpect(jsonPath("$.data.items[0].action.enabled").value(false))
                .andExpect(jsonPath("$.data.items[0].message").value(n.message()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,0", "59,0", "60,1", "1500,25"})
    void manualMessageUsesShareTimeAndTruncatesHours(long minutes, long hours) {
        recorder.record(request());
        replaceShareCreatedAt(NOW.minusMinutes(minutes));
        writer.manual(settlement, participant, creator);
        var saved = notifications.findPage(recipient, null, null, 10);
        assertThat(saved).filteredOn(n -> n.type() == com.amateur.howmuchbackend.domain.notification.NotificationType.SETTLEMENT_REMINDER)
                .extracting(UserNotification::message)
                .containsExactly("정산을 요청받은지 " + hours + "시간이나 지났어요");
    }

    @Test
    void itemReminder_requestedWithoutShare_persistsSelectionMessage() {
        jdbc.update("DELETE FROM settlement_shares WHERE id=?", share);
        jdbc.update("UPDATE settlements SET allocation_type='ITEM_ALLOCATION', status='REQUESTED' WHERE id=?", settlement);

        writer.manual(settlement, participant, creator);

        assertThat(shareReader.findBySettlementIdAndPayerParticipantId(settlement, participant)).isEmpty();
        assertThat(notifications.findPage(recipient, null, null, 10))
                .filteredOn(n -> n.type() == com.amateur.howmuchbackend.domain.notification.NotificationType.SETTLEMENT_REMINDER)
                .extracting(UserNotification::allocationType, UserNotification::message)
                .containsExactly(tuple(
                        SettlementAllocationType.ITEM_ALLOCATION,
                        "알림 테스트님이 정산 품목 선택을 기다리고 있어요"
                ));
        assertThat(cooldown.findRemainingCooldown(settlement, participant)).isPresent();
    }

    @Test
    void itemAllocationReminder_submittedWithoutShare_excluded() {
        jdbc.update("DELETE FROM settlement_shares WHERE id=?", share);
        jdbc.update("UPDATE settlements SET allocation_type='ITEM_ALLOCATION', status='REQUESTED' WHERE id=?", settlement);
        itemAllocations.saveSubmission(SettlementItemSubmission.submit(
                settlement, participant, NOW.minusMinutes(1)
        ));

        assertThatThrownBy(() -> writer.manual(settlement, participant, creator))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID));
        assertThat(notifications.countUnread(recipient)).isZero();
        assertThat(cooldown.findRemainingCooldown(settlement, participant)).isEmpty();
    }

    @Test
    void automaticReminder_quietHoursDeferred_repeatsFromDeliveryTime() {
        recorder.record(request());
        LocalDateTime quietStart = LocalDateTime.of(2026, 9, 5, 18, 0); // KST 03:00
        replaceShareCreatedAt(quietStart.minusHours(12));
        when(clock.instant()).thenReturn(quietStart.toInstant(ZoneOffset.UTC));

        assertThat(automaticReminders.sendDue(50)).isZero();
        assertThat(notifications.countUnread(recipient)).isOne();

        LocalDateTime deferredAt = LocalDateTime.of(2026, 9, 5, 21, 0); // KST 06:00
        when(clock.instant()).thenReturn(deferredAt.toInstant(ZoneOffset.UTC));
        assertThat(automaticReminders.sendDue(50)).isOne();
        assertThat(automaticReminders.sendDue(50)).isZero();

        redis.delete("settlement:reminder:" + settlement + ":" + participant);
        when(clock.instant()).thenReturn(deferredAt.plusHours(12).minusSeconds(1)
                .toInstant(ZoneOffset.UTC));
        assertThat(automaticReminders.sendDue(50)).isZero();
        when(clock.instant()).thenReturn(deferredAt.plusHours(12).toInstant(ZoneOffset.UTC));
        assertThat(automaticReminders.sendDue(50)).isOne();

        assertThat(notifications.findPage(recipient, null, null, 10))
                .filteredOn(n -> n.type() == com.amateur.howmuchbackend.domain.notification.NotificationType.SETTLEMENT_REMINDER)
                .extracting(UserNotification::message)
                .containsExactly(
                        "정산을 요청받은지 27시간이나 지났어요",
                        "정산을 요청받은지 15시간이나 지났어요"
                );
    }

    @Test
    void reminder_existingMessage_notRewritten() throws Exception {
        var old = NotificationMessages.reminder(settlement, recipient, SettlementAllocationType.DUTCH_PAY,
                "요청자", "legacy-message", "정산을 요청받았어요. 지금 정산을 완료해 주세요.", NOW.minusDays(1));
        recorder.record(old);
        writer.manual(settlement, participant, creator);
        when(clock.instant()).thenReturn(NOW.plusDays(1).toInstant(ZoneOffset.UTC));
        mvc.perform(get("/api/v2/notifications").with(user("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].message").value("정산을 요청받은지 25시간이나 지났어요"))
                .andExpect(jsonPath("$.data.items[1].message").value(old.message()));
    }

    @Test
    void notificationOutbox_failure_rollsBackInboxAndCooldown() {
        doThrow(new IllegalStateException("injected outbox failure")).when(outbox).saveAll(anyList());
        assertThatThrownBy(() -> writer.manual(settlement, participant, creator)).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.countUnread(recipient)).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(cooldown.findRemainingCooldown(settlement, participant)).isEmpty();
    }

    @Test
    void pushToken_absent_preservesInboxAndCooldown() {
        writer.manual(settlement, participant, creator);
        jdbc.update("UPDATE settlement_notification_outbox SET next_attempt_at=? WHERE settlement_id=?", NOW.minusDays(2), settlement);
        assertThat(worker.processBatch(1).failed()).isOne();
        assertThat(notifications.countUnread(recipient)).isOne();
        assertThat(cooldown.findRemainingCooldown(settlement, participant)).isPresent();
    }

    @Test
    void automaticReminder_multipleWorkersAndRestart_createOne() throws Exception {
        recorder.record(request());
        assertThat(due()).containsExactly(candidate());
        assertThat(shareReader.findBySettlementIdAndPayerParticipantId(settlement, participant).orElseThrow().createdAt())
                .isEqualTo(NOW.minusHours(25));
        assertThat(concurrent(() -> attemptAutomatic(), () -> attemptAutomatic())).contains(true);
        assertThat(notifications.countUnread(recipient)).isEqualTo(2);
        assertThat(countOutbox()).isEqualTo(2);
        assertThat(writer.automatic(candidate())).isFalse();
        assertThat(due()).isEmpty();
    }

    @Test
    void reminder_manualAndAutomatic_shareCooldown() throws Exception {
        recorder.record(request());
        concurrent(() -> attemptAutomatic(), () -> {
            try { writer.manual(settlement, participant, creator); return true; }
            catch (com.amateur.howmuchbackend.common.exception.ApplicationException ex) {
                assertThat(ex.getStatusCode()).isEqualTo(com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE);
                return false;
            }
        });
        assertThat(notifications.countUnread(recipient)).isEqualTo(2);
        assertThat(countOutbox()).isEqualTo(2);
    }

    @Test
    void automaticReminder_candidates_filteredAndOrderedByDueTime() {
        assertThat(due()).isEmpty();
        recorder.record(request());
        assertThat(due()).hasSize(1);
        jdbc.update("UPDATE users SET active=false WHERE id=?", recipient);
        assertThat(due()).isEmpty();
        jdbc.update("UPDATE users SET active=true WHERE id=?", recipient);
        jdbc.update("UPDATE settlements SET status='COMPLETED' WHERE id=?", settlement);
        assertThat(due()).isEmpty();
        assertThat(writer.automatic(candidate())).isFalse();
        jdbc.update("UPDATE settlements SET status='REQUESTED' WHERE id=?", settlement);
        assertThat(writer.automatic(candidate())).isTrue();

        var earlierDueCandidate = insertAutomaticCandidate(NOW.minusHours(1));
        try {
            assertThat(targets.findDue(NOW, NOW.plusHours(11).plusMinutes(30), 1))
                    .containsExactly(earlierDueCandidate);
        } finally {
            jdbc.update("DELETE FROM settlements WHERE id=?", earlierDueCandidate.settlementId());
        }

        jdbc.update("DELETE FROM settlement_shares WHERE id=?", share);
        assertThat(due()).isEmpty();
    }

    @Test
    void paymentAfterCandidateQuery_recheckedBeforeReminder() {
        recorder.record(request());
        assertThat(due()).hasSize(1);
        Long transaction = jdbc.queryForObject("""
                INSERT INTO account_transactions(account_id,external_transaction_key,transaction_type,amount,transacted_at)
                VALUES (?,?,'INCOME',1,?) RETURNING id
                """, Long.class, account, "a".repeat(64), NOW);
        jdbc.update("INSERT INTO settlement_deposits(id,settlement_id,share_id,source_account_id,account_transaction_id,matched_at) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), settlement, share, account, transaction, NOW);
        assertThat(due()).isEmpty();
        assertThatThrownBy(() -> writer.automatic(candidate())).isInstanceOf(com.amateur.howmuchbackend.common.exception.ApplicationException.class);
        assertThat(notifications.countUnread(recipient)).isOne();
    }

    @Test
    void notificationInbox_outerStateChange_rollsBackTogether() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?", settlement);
            recorder.record(request());
            throw new IllegalStateException("request failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.countUnread(recipient)).isZero();
        assertThat(countOutbox()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?", String.class, settlement)).isEqualTo("AWAITING_DEPOSIT");
    }

    @Test
    void notificationOutbox_staleClaim_recoveredForNextWorker() {
        var n = request(); recorder.record(n);
        var claimed = outbox.claimNextReady(NOW).orElseThrow();
        assertThat(claimed.notificationId()).isEqualTo(n.id());
        assertThat(outbox.recoverStale(10, NOW.plusMinutes(1), NOW.plusMinutes(2), 5)).isOne();
        var recovered = outbox.claimNextReady(NOW.plusMinutes(3)).orElseThrow();
        assertThat(recovered.notificationId()).isEqualTo(n.id());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(notifications.countUnread(recipient)).isOne();
    }

    @Test
    void automaticReminder_afterManualReminder_defersToCooldown() {
        recorder.record(request());
        writer.manual(settlement, participant, creator);
        assertThat(due()).isEmpty();
        when(clock.instant()).thenReturn(NOW.plusMinutes(31).toInstant(ZoneOffset.UTC));
        // 실제 대기하거나 다른 테스트 키를 변경하지 않고 Redis TTL 만료를 재현한다.
        redis.delete("settlement:reminder:" + settlement + ":" + participant);
        assertThat(targets.findDue(NOW.plusMinutes(31).minusHours(12), NOW.plusMinutes(1), 50)).hasSize(1);
        assertThat(writer.automatic(candidate())).isTrue();
        assertThat(notifications.countUnread(recipient)).isEqualTo(3);
    }

    @Test
    void openApiDocumentsNotificationEnumsReadStateAndSecurity() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = json.readTree(response.getResponse().getContentAsString());
        var item = document.at("/components/schemas/NotificationItem");
        assertThat(item.get("required").toString()).contains("readAt", "action", "settlementId", "createdAt");
        assertThat(item.at("/properties/type/enum").toString()).contains("SETTLEMENT_REQUEST", "SETTLEMENT_REMINDER");
        assertThat(document.at("/paths/~1api~1v2~1notifications/get/security").toString()).contains("bearerAuth");
    }

    private void replaceShareCreatedAt(LocalDateTime createdAt) {
        // Use the production JPA mapping so JDBC and Hibernate timezone handling cannot diverge.
        jdbc.update("DELETE FROM settlement_shares WHERE id=?", share);
        shareStore.saveAll(List.of(new com.amateur.howmuchbackend.domain.settlement.SettlementShare(
                share, settlement, participant, 1000, createdAt)));
    }

    private UserNotification request() {
        return NotificationMessages.request(settlement, recipient, SettlementAllocationType.DUTCH_PAY, "요청 당시 이름", NOW.minusHours(25));
    }
    private SettlementAutomaticReminderTargetPort.Target candidate() {
        return new SettlementAutomaticReminderTargetPort.Target(settlement, participant, share, recipient);
    }
    private SettlementAutomaticReminderTargetPort.Target insertAutomaticCandidate(LocalDateTime shareCreatedAt) {
        UUID candidateSettlement = UUID.randomUUID();
        UUID candidateParticipant = UUID.randomUUID();
        UUID candidateShare = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,created_at,updated_at)
                VALUES (?,?,'후속 알림 검증',1000,'DUTCH_PAY','AWAITING_DEPOSIT',?,?)
                """, candidateSettlement, creator, shareCreatedAt.minusHours(1), shareCreatedAt);
        jdbc.update("""
                INSERT INTO settlement_receiving_account_snapshots
                (settlement_id,source_account_id,organization,account_number_ciphertext,account_holder,created_at)
                VALUES (?,?,'0020','test-ciphertext','예금주',?)
                """, candidateSettlement, account, shareCreatedAt.minusHours(1));
        jdbc.update("""
                INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,display_name,display_order,created_at,updated_at)
                VALUES (?,?,'REGISTERED_USER',?,'참여자',0,?,?)
                """, candidateParticipant, candidateSettlement, recipient, shareCreatedAt.minusHours(1), shareCreatedAt);
        shareStore.saveAll(List.of(new com.amateur.howmuchbackend.domain.settlement.SettlementShare(
                candidateShare, candidateSettlement, candidateParticipant, 1000, shareCreatedAt)));
        recorder.record(NotificationMessages.request(candidateSettlement, recipient, SettlementAllocationType.DUTCH_PAY,
                "요청 당시 이름", shareCreatedAt));
        return new SettlementAutomaticReminderTargetPort.Target(
                candidateSettlement, candidateParticipant, candidateShare, recipient);
    }
    private List<SettlementAutomaticReminderTargetPort.Target> due() {
        return targets.findDue(NOW.minusHours(12), NOW.minusMinutes(30), 50);
    }
    private boolean attemptAutomatic() {
        try { return writer.automatic(candidate()); }
        catch (com.amateur.howmuchbackend.common.exception.ApplicationException ex) {
            assertThat(ex.getStatusCode()).isEqualTo(com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE);
            return false;
        }
    }
    private Long countOutbox() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_notification_outbox WHERE settlement_id=?", Long.class, settlement);
    }
    private Long insertUser() {
        String unique = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)
                VALUES (?,'알림 테스트',?,?,'unused',true,'USER',true,?,?) RETURNING id
                """, Long.class, unique, "n_" + unique.replace("-", "").substring(0, 10), unique.substring(0, 20), NOW, NOW);
    }
    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            var a = executor.submit(() -> { latch.await(); return first.call(); });
            var b = executor.submit(() -> { latch.await(); return second.call(); });
            latch.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
}
