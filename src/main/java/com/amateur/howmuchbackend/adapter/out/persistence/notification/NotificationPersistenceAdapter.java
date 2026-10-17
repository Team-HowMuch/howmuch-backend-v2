package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.mapper.NotificationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.repository.UserNotificationJpaRepository;
import com.amateur.howmuchbackend.application.port.out.notification.*;
import com.amateur.howmuchbackend.domain.notification.NotificationTarget;
import com.amateur.howmuchbackend.domain.notification.NotificationTargetType;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements LoadNotificationPort, MarkNotificationReadPort, SaveNotificationPort {
    private final UserNotificationJpaRepository repository;
    private final Clock clock;
    private final com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting
                    .NettingNotificationJdbcAdapter
            nettingTargets;
    @Override
    @Transactional(readOnly = true)
    public List<UserNotification> findPage(Long userId, LocalDateTime beforeAt, UUID beforeId, int limit) {
        var rows = beforeAt == null ? repository.firstPage(userId, limit)
                : repository.before(userId, beforeAt, beforeId, limit);
        var settlementIds = rows.stream().map(UserNotificationJpaEntity::getSettlementId)
                .filter(Objects::nonNull).distinct().toList();
        Set<UUID> deletedIds = settlementIds.isEmpty() ? Set.of()
                : Set.copyOf(repository.findDeletedSettlementIds(settlementIds));
        return toVisibleNotifications(rows, userId, deletedIds);
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
                .map(row -> toVisibleNotifications(List.of(row), row.getRecipientUserId(), Set.of()).getFirst());
    }
    @Override
    @Transactional
    public boolean saveIfAbsent(UserNotification notification) {
        if (notification.target() == null)
            throw new IllegalArgumentException("새 알림에는 이동 대상이 필요합니다.");
        return repository.insertIfAbsent(notification) == 1;
    }
    private List<UserNotification> toVisibleNotifications(List<UserNotificationJpaEntity> rows, Long userId,
                                                         Set<UUID> deletedSettlementIds) {
        var groupIds = rows.stream().filter(row -> row.getTargetType() == NotificationTargetType.GROUP_DETAIL)
                .map(UserNotificationJpaEntity::getTargetId).filter(Objects::nonNull)
                .map(Long::valueOf).distinct().toList();
        var invitationIds = rows.stream().filter(row -> row.getTargetType() == NotificationTargetType.GROUP_INVITATION)
                .map(UserNotificationJpaEntity::getTargetId).filter(Objects::nonNull)
                .map(UUID::fromString).distinct().toList();
        Set<Long> accessibleGroups = groupIds.isEmpty() ? Set.of()
                : Set.copyOf(repository.findAccessibleGroupIds(groupIds, userId));
        Set<UUID> accessibleInvitations = invitationIds.isEmpty() ? Set.of()
                : Set.copyOf(repository.findAccessibleInvitationIds(invitationIds, userId,
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        return rows.stream().map(row -> {
            boolean deletedSettlement = row.getSettlementId() != null
                    && deletedSettlementIds.contains(row.getSettlementId());
            return NotificationMapper.toDomain(row, deletedSettlement ? null : row.getSettlementId(),
                    visibleTarget(row, deletedSettlement, accessibleGroups, accessibleInvitations));
        }).toList();
    }
    private NotificationTarget visibleTarget(UserNotificationJpaEntity row, boolean deletedSettlement,
                                              Set<Long> accessibleGroups, Set<UUID> accessibleInvitations) {
        if (row.getTargetId() == null || deletedSettlement) return null;
        boolean accessible = switch (row.getTargetType()) {
            case SETTLEMENT_PARTICIPATION, SETTLEMENT_DETAIL -> row.getSettlementId() != null;
            case GROUP_DETAIL -> accessibleGroups.contains(Long.valueOf(row.getTargetId()));
            case GROUP_INVITATION -> accessibleInvitations.contains(UUID.fromString(row.getTargetId()));
            case MONTHLY_RECAP -> true;
                    case GROUP_SETTLEMENT_NETTING ->
                            nettingTargets.visible(
                                    UUID.fromString(row.getTargetId()), row.getRecipientUserId());
                };
        return accessible ? new NotificationTarget(row.getTargetType(), row.getTargetId()) : null;
    }
}
