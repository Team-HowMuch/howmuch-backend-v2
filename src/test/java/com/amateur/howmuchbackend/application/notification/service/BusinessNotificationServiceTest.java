package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.group.*;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.group.*;
import com.amateur.howmuchbackend.domain.notification.*;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessNotificationServiceTest {
    private final NotificationRecorder recorder = mock(NotificationRecorder.class);
    private final LoadSettlementParticipantPort participants = mock(LoadSettlementParticipantPort.class);
    private final LoadGroupMemberPort members = mock(LoadGroupMemberPort.class);
    private final LoadGroupPort groups = mock(LoadGroupPort.class);
    private final LoadUserPort users = mock(LoadUserPort.class);
    private final BusinessNotificationService service = new BusinessNotificationService(recorder, participants, members, groups, users);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 0, 0);
    @Test void depositConfirmed_notifiesCreatorWithRemainingAmount() {
        active(1L);
        var settlement = settlement(); var share = UUID.randomUUID();
        service.settlementDepositConfirmed(settlement, share, "납부자", 500, NOW);
        var captor = ArgumentCaptor.forClass(UserNotification.class); verify(recorder).record(captor.capture());
        var n = captor.getValue();
        assertThat(n.recipientUserId()).isEqualTo(1L);
        assertThat(n.message()).contains("납부자", "500원");
        assertThat(n.type()).isEqualTo(NotificationType.SETTLEMENT_DEPOSIT_CONFIRMED);
        assertThat(n.deduplicationKey()).isEqualTo("DEPOSIT_CONFIRMED:" + share);
        assertThat(n.target()).isEqualTo(new NotificationTarget(NotificationTargetType.SETTLEMENT_DETAIL, settlement.id().toString()));
    }
    @Test void completed_notifiesDistinctLinkedActiveParticipantsOnly() {
        var settlement = settlement(); active(1L); active(2L);
        var one = mock(SettlementParticipant.class); var two = mock(SettlementParticipant.class); var nonmember = mock(SettlementParticipant.class);
        when(one.linkedUserId()).thenReturn(1L); when(two.linkedUserId()).thenReturn(2L);
        when(participants.findAllBySettlementId(settlement.id())).thenReturn(List.of(one, two, one, nonmember));
        service.settlementCompleted(settlement, List.of("납부자"), NOW);
        var captor = ArgumentCaptor.forClass(UserNotification.class); verify(recorder, times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(UserNotification::recipientUserId).containsExactly(1L, 2L);
        assertThat(captor.getAllValues()).allMatch(n -> n.type() == NotificationType.SETTLEMENT_COMPLETED);
        assertThat(captor.getAllValues().getFirst().title()).isEqualTo("모두 받았어요");
        assertThat(captor.getAllValues().getFirst().message()).contains("납부자", "0원");
    }
    @Test void departedMember_excludedFromRecipients() {
        active(1L); active(2L); var departed = active(3L); when(departed.name()).thenReturn("탈퇴자");
        var group = mock(Group.class); when(group.name()).thenReturn("모임"); when(groups.findById(10L)).thenReturn(Optional.of(group));
        when(members.findActiveByGroupId(10L)).thenReturn(List.of(GroupMember.owner(10L, 1L, NOW), GroupMember.member(10L, 2L, NOW), GroupMember.member(10L, 3L, NOW)));
        service.groupMemberLeft(10L, 30L, 3L, NOW);
        var captor = ArgumentCaptor.forClass(UserNotification.class); verify(recorder, times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(UserNotification::recipientUserId).containsExactly(1L, 2L);
        assertThat(captor.getAllValues()).allMatch(n -> n.settlementId() == null && n.allocationType() == null);
    }
    @Test void inactiveRecipient_skipped() {
        service.recapPublished(9L, YearMonth.of(2026, 9), NOW);
        verifyNoInteractions(recorder);
    }
    private User active(Long id) { var user = mock(User.class); when(user.active()).thenReturn(true); when(users.findById(id)).thenReturn(Optional.of(user)); return user; }
    private Settlement settlement() { return new Settlement(UUID.randomUUID(), 1L, "정산", 1000, SettlementAllocationType.DUTCH_PAY, SettlementStatus.COMPLETED, NOW, NOW); }
}
