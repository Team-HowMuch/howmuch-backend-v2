package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.mapper.NotificationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.repository.UserNotificationJpaRepository;
import com.amateur.howmuchbackend.application.port.out.notification.*;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import com.amateur.howmuchbackend.domain.notification.NotificationTarget;
import java.time.Clock;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements LoadNotificationPort, MarkNotificationReadPort, SaveNotificationPort {
    private final UserNotificationJpaRepository repository;
    private final Clock clock;
    @Override
    @Transactional(readOnly = true)
    public List<UserNotification> findPage(Long userId, LocalDateTime beforeAt, UUID beforeId, int limit) {
        var rows = beforeAt == null ? repository.firstPage(userId, limit)
                : repository.before(userId, beforeAt, beforeId, limit);
        var settlementIds = rows.stream().map(UserNotificationJpaEntity::getSettlementId)
                .filter(Objects::nonNull).distinct().toList();
        Set<UUID> deletedIds = settlementIds.isEmpty() ? Set.of()
                : Set.copyOf(repository.findDeletedSettlementIds(settlementIds));
        return rows.stream().map(row -> NotificationMapper.toDomain(row,
                deletedIds.contains(row.getSettlementId()) ? null : row.getSettlementId(),
                visibleTarget(row, deletedIds.contains(row.getSettlementId())))).toList();
    }
    @Override
    @Transactional(readOnly = true)
    public long countUnread(Long userId) { return repository.countByRecipientUserIdAndReadAtIsNull(userId); }
    @Override
    @Transactional
    public Optional<LocalDateTime> markRead(UUID id, Long userId, LocalDateTime now) {
        if (repository.markRead(id, userId, now) == 0) return Optional.empty();
        return repository.findByIdAndRecipientUserId(id, userId).map(e -> e.getReadAt());
    }
    @Override
    @Transactional(readOnly = true)
    public Optional<UserNotification> findById(UUID id) {
        return repository.findById(id)
                .map(row -> NotificationMapper.toDomain(row, row.getSettlementId(), visibleTarget(row, false)));
    }
    @Override
    @Transactional
    public boolean saveIfAbsent(UserNotification notification) {
        if (notification.target() == null)
            throw new IllegalArgumentException("새 알림에는 이동 대상이 필요합니다.");
        return repository.insertIfAbsent(notification) == 1;
    }
    private NotificationTarget visibleTarget(UserNotificationJpaEntity row, boolean deletedSettlement) {
        if (row.getTargetId() == null || deletedSettlement) return null;
        boolean accessible = switch (row.getTargetType()) {
            case SETTLEMENT_PARTICIPATION, SETTLEMENT_DETAIL -> row.getSettlementId() != null;
            case GROUP_DETAIL -> repository.isGroupAccessible(Long.valueOf(row.getTargetId()), row.getRecipientUserId());
            case GROUP_INVITATION -> repository.isInvitationAccessible(UUID.fromString(row.getTargetId()),
                    row.getRecipientUserId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            case MONTHLY_RECAP -> true;
        };
        return accessible ? new NotificationTarget(row.getTargetType(), row.getTargetId()) : null;
    }
}
