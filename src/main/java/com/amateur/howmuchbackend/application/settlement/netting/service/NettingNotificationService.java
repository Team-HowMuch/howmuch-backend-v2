package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.domain.notification.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NettingNotificationService {
    private final NotificationRecorder recorder;
    private final LoadGroupMemberPort members;
    private final LoadGroupNettingPort nettings;

    public void confirmed(UUID id, long group, LocalDateTime now) {
        for (long recipient : recipients(id, group))
            record(
                    recipient,
                    id,
                    NotificationType.GROUP_NETTING_CONFIRMED,
                    NotificationTargetType.GROUP_SETTLEMENT_NETTING,
                    "그룹 압축 정산이 확정됐어요",
                    "변경된 수취인과 최종 송금 금액을 확인해주세요.",
                    "NETTING_CONFIRMED:" + id,
                    now);
    }

    public void completed(UUID id, long group, LocalDateTime now) {
        for (long recipient : recipients(id, group))
            record(
                    recipient,
                    id,
                    NotificationType.GROUP_NETTING_COMPLETED,
                    NotificationTargetType.GROUP_SETTLEMENT_NETTING,
                    "그룹 압축 정산이 완료됐어요",
                    "상계와 최종 송금이 모두 완료됐어요.",
                    "NETTING_COMPLETED:" + id,
                    now);
    }

    public void reminder(LoadGroupNettingPort.Transfer transfer, String dedup, LocalDateTime now) {
        record(
                transfer.payerId(),
                transfer.nettingId(),
                NotificationType.NETTED_TRANSFER_REMINDER,
                NotificationTargetType.GROUP_SETTLEMENT_NETTING,
                "압축 정산 송금을 확인해주세요",
                "최종 송금 안내에서 금액과 받는 분 통장 표시를 확인해주세요.",
                dedup,
                now);
    }

    private List<Long> recipients(UUID id, long group) {
        var parties = nettings.findById(id).orElseThrow().partyIds();
        return members.findActiveByGroupId(group).stream()
                .filter(member -> parties.contains(member.userId()))
                .map(com.amateur.howmuchbackend.domain.group.GroupMember::userId)
                .toList();
    }

    private void record(
            long user,
            UUID id,
            NotificationType type,
            NotificationTargetType target,
            String title,
            String message,
            String dedup,
            LocalDateTime now) {
        recorder.record(
                new UserNotification(
                        UUID.randomUUID(),
                        user,
                        null,
                        type,
                        null,
                        "얼마",
                        title,
                        message,
                        dedup,
                        null,
                        now,
                        new NotificationTarget(target, id.toString())));
    }
}
