package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import com.amateur.howmuchbackend.application.port.out.notification.LoadNotificationPort;
import com.amateur.howmuchbackend.application.port.out.notification.SaveNotificationPort;
import com.amateur.howmuchbackend.domain.notification.*;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, properties = {
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
@Transactional
class NotificationTargetAccessIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 0, 0);
    @Autowired LoadNotificationPort notifications;
    @Autowired SaveNotificationPort save;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entities;
    @MockitoBean Clock clock;
    private Long owner, recipient, other;
    private Statistics statistics;

    @BeforeEach void setUp() {
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        owner = user(); recipient = user(); other = user();
        statistics = entities.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach void stopStatistics() { statistics.setStatisticsEnabled(false); }

    @Test void maximumMixedPage_hasFourQueries_andPreservesVisibilityAndCursor() {
        Long active = group(owner, false);
        member(active, "ACTIVE");
        Long left = group(owner, false); member(left, "LEFT");
        Long removed = group(owner, false); member(removed, "REMOVED");
        Long deleted = group(owner, true); member(deleted, "ACTIVE");
        UUID valid = invitation(active, owner, recipient, "PENDING", NOW.plusDays(1));
        UUID expired = invitation(group(owner, false), owner, recipient, "PENDING", NOW);
        UUID declined = invitation(group(owner, false), owner, recipient, "DECLINED", NOW.plusDays(1));
        UUID changedOwner = invitation(group(other, false), owner, recipient, "PENDING", NOW.plusDays(1));
        UUID wrongRecipient = invitation(group(owner, false), owner, other, "PENDING", NOW.plusDays(1));
        UUID deletedGroup = invitation(deleted, owner, recipient, "PENDING", NOW.plusDays(1));
        UUID deletedSettlement = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,created_at,updated_at,deleted_at)
                VALUES (?,?,'삭제 정산',1000,'DUTCH_PAY','AWAITING_DEPOSIT',?,?,?)
                """, deletedSettlement, owner, NOW.minusDays(1), NOW, NOW);
        var cases = List.of(groupCase(active, true), groupCase(left, false), groupCase(removed, false),
                groupCase(deleted, false), invitationCase(valid, true), invitationCase(expired, false),
                invitationCase(declined, false), invitationCase(changedOwner, false),
                invitationCase(wrongRecipient, false), invitationCase(deletedGroup, false),
                new TargetCase(NotificationType.MONTHLY_RECAP,
                        new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09"), null, true),
                new TargetCase(NotificationType.SETTLEMENT_COMPLETED,
                        new NotificationTarget(NotificationTargetType.SETTLEMENT_DETAIL, deletedSettlement.toString()),
                        deletedSettlement, false));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 52; i++) ids.add(record(recipient, cases.get(i % cases.size()), NOW.minusSeconds(i)));
        record(other, groupCase(active, true), NOW.plusSeconds(1));

        statistics.clear();
        var page = notifications.findPage(recipient, null, null, 51);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(page).extracting(UserNotification::id).containsExactlyElementsOf(ids.subList(0, 51));
        for (int i = 0; i < page.size(); i++) {
            var expected = cases.get(i % cases.size());
            assertThat(page.get(i).target()).as("target at index %s: %s", i, expected.target())
                    .isEqualTo(expected.visible() ? expected.target() : null);
            assertThat(page.get(i).settlementId()).isNull();
            assertThat(page.get(i).message()).isEqualTo("저장된 알림 본문");
        }
        var last = page.getLast();
        assertThat(notifications.findPage(recipient, last.createdAt(), last.id(), 51))
                .extracting(UserNotification::id).containsExactly(ids.getLast());
    }

    @Test void emptyAndRecapOnlyPages_skipAllTargetQueries() {
        statistics.clear();
        assertThat(notifications.findPage(recipient, null, null, 51)).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isOne();
        var recap = new TargetCase(NotificationType.MONTHLY_RECAP,
                new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09"), null, true);
        record(recipient, recap, NOW);
        statistics.clear();
        assertThat(notifications.findPage(recipient, null, null, 51)).singleElement()
                .extracting(UserNotification::target).isEqualTo(recap.target());
        assertThat(statistics.getPrepareStatementCount()).isOne();
    }

    @Test void singleRead_andPageReflectOwnerChangeAndMemberExit() {
        Long group = group(owner, false); member(group, "ACTIVE");
        UUID invitation = invitation(group, owner, recipient, "PENDING", NOW.plusDays(1));
        UUID inviteNote = record(recipient, invitationCase(invitation, true), NOW);
        UUID groupNote = record(recipient, groupCase(group, true), NOW.minusSeconds(1));
        assertThat(notifications.findById(inviteNote).orElseThrow().target()).isNotNull();
        assertThat(notifications.findById(groupNote).orElseThrow().target()).isNotNull();
        jdbc.update("UPDATE groups SET owner_id=? WHERE id=?", other, group);
        jdbc.update("UPDATE group_members SET status='LEFT',left_at=? WHERE group_id=? AND user_id=?", NOW, group, recipient);
        assertThat(notifications.findById(inviteNote).orElseThrow().target()).isNull();
        assertThat(notifications.findById(groupNote).orElseThrow().target()).isNull();
        assertThat(notifications.findPage(recipient, null, null, 51))
                .extracting(UserNotification::target).containsOnlyNulls();
    }

    private Long user() {
        String unique = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)
                VALUES (?,'조회 검증',?,?,'unused',true,'USER',true,?,?) RETURNING id
                """, Long.class, unique, "q_" + unique.replace("-", "").substring(0, 10), unique.substring(0, 20), NOW, NOW);
    }

    private Long group(Long groupOwner, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO groups(name,owner_id,invite_code,status,creator_id,created_at,updated_at,deleted_at)
                VALUES ('조회 그룹',?,?,?,?,?,?,?) RETURNING id
                """, Long.class, groupOwner, UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT),
                deleted ? "DELETED" : "ACTIVE", owner, NOW.minusDays(1), NOW, deleted ? NOW : null);
    }

    private void member(Long groupId, String status) {
        jdbc.update("""
                INSERT INTO group_members(group_id,user_id,role,status,joined_at,left_at,created_at,updated_at)
                VALUES (?,?,'MEMBER',?,?,?,?,?)
                """, groupId, recipient, status, NOW.minusDays(1), status.equals("ACTIVE") ? null : NOW,
                NOW.minusDays(1), NOW);
    }

    private UUID invitation(Long groupId, Long inviter, Long invitee, String status, LocalDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO group_invitations(id,group_id,inviter_user_id,recipient_user_id,status,expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, groupId, inviter, invitee, status, expiresAt, NOW.minusDays(1), NOW);
        return id;
    }

    private UUID record(Long userId, TargetCase target, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        save.saveIfAbsent(new UserNotification(id, userId, target.settlementId(), target.type(),
                target.settlementId() == null ? null : SettlementAllocationType.DUTCH_PAY,
                "검증", "알림 조회 검증", "저장된 알림 본문", "query:" + id, null, createdAt, target.target()));
        return id;
    }
    private TargetCase groupCase(Long id, boolean visible) {
        return new TargetCase(NotificationType.GROUP_MEMBER_LEFT,
                new NotificationTarget(NotificationTargetType.GROUP_DETAIL, id.toString()), null, visible);
    }
    private TargetCase invitationCase(UUID id, boolean visible) {
        return new TargetCase(NotificationType.GROUP_INVITATION,
                new NotificationTarget(NotificationTargetType.GROUP_INVITATION, id.toString()), null, visible);
    }
    private record TargetCase(NotificationType type, NotificationTarget target, UUID settlementId, boolean visible) {}
}
