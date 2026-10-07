package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.group.GroupInvitation;
import com.amateur.howmuchbackend.domain.notification.*;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessNotificationService {
    private final NotificationRecorder recorder;
    private final LoadSettlementParticipantPort participants;
    private final LoadGroupMemberPort members;
    private final LoadGroupPort groups;
    private final LoadUserPort users;

    public void settlementPaymentCompleted(Settlement settlement, UUID shareId, String payerName,
                                           LocalDateTime now) {
        record(settlement.creatorId(), settlement.id(), NotificationType.SETTLEMENT_PAYMENT_COMPLETED,
                settlement.allocationType(), payerName, "송금이 확인되었어요",
                payerName + "님의 " + settlement.title() + " 송금이 확인되었어요.",
                "SHARE_PAID:" + shareId, NotificationTargetType.SETTLEMENT_DETAIL,
                settlement.id().toString(), now);
    }

    public void settlementCompleted(Settlement settlement, LocalDateTime now) {
        participants.findAllBySettlementId(settlement.id()).stream()
                .map(SettlementParticipant::linkedUserId).filter(Objects::nonNull).distinct()
                .forEach(recipient -> record(recipient, settlement.id(), NotificationType.SETTLEMENT_COMPLETED,
                        settlement.allocationType(), "HowMuch", "정산이 완료되었어요",
                        settlement.title() + "의 모든 송금이 확인되었어요.",
                        "SETTLEMENT_COMPLETED:" + settlement.id(), NotificationTargetType.SETTLEMENT_DETAIL,
                        settlement.id().toString(), now));
    }

    public void groupInvitation(GroupInvitation invitation, String groupName, String inviterName) {
        record(invitation.recipientUserId(), null, NotificationType.GROUP_INVITATION, null,
                inviterName, "그룹 초대가 도착했어요", inviterName + "님이 " + groupName + "에 초대했어요.",
                "GROUP_INVITATION:" + invitation.id(), NotificationTargetType.GROUP_INVITATION,
                invitation.id().toString(), invitation.createdAt());
    }

    public void groupMemberLeft(Long groupId, Long membershipId, Long departedUserId, LocalDateTime now) {
        String groupName = groups.findById(groupId).orElseThrow().name();
        String name = users.findById(departedUserId).map(User::name).orElse("회원");
        members.findActiveByGroupId(groupId).stream().map(member -> member.userId())
                .filter(recipient -> !recipient.equals(departedUserId)).distinct()
                .forEach(recipient -> record(recipient, null, NotificationType.GROUP_MEMBER_LEFT, null,
                        name, "그룹 멤버가 탈퇴했어요", name + "님이 " + groupName + "에서 탈퇴했어요.",
                        "GROUP_MEMBER_LEFT:" + membershipId + ":" + now,
                        NotificationTargetType.GROUP_DETAIL, groupId.toString(), now));
    }

    public void recapPublished(Long userId, YearMonth month, LocalDateTime now) {
        record(userId, null, NotificationType.MONTHLY_RECAP, null, "HowMuch",
                month.getMonthValue() + "월 리캡이 도착했어요", "지난달 정산과 소비를 리캡에서 확인해 보세요.",
                "RECAP_PUBLISHED:" + month, NotificationTargetType.MONTHLY_RECAP, month.toString(), now);
    }

    private void record(Long recipient, UUID settlementId, NotificationType type,
                        SettlementAllocationType allocation, String actor, String title, String message,
                        String deduplicationKey, NotificationTargetType targetType, String targetId,
                        LocalDateTime now) {
        if (users.findById(recipient).filter(User::active).isEmpty()) return;
        recorder.record(new UserNotification(UUID.randomUUID(), recipient, settlementId, type, allocation,
                actor, title, message, deduplicationKey, null, now,
                new NotificationTarget(targetType, targetId)));
    }
}
