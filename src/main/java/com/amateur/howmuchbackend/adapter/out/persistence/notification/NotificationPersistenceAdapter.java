package com.amateur.howmuchbackend.adapter.out.persistence.notification;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.mapper.NotificationMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.notification.repository.UserNotificationJpaRepository;
import com.amateur.howmuchbackend.application.port.out.notification.*;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NotificationPersistenceAdapter implements LoadNotificationPort, MarkNotificationReadPort, SaveNotificationPort {
    private final UserNotificationJpaRepository repository;
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
                deletedIds.contains(row.getSettlementId()) ? null : row.getSettlementId())).toList();
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
                .map(row -> NotificationMapper.toDomain(row, row.getSettlementId()));
    }
    @Override
    @Transactional
    public boolean saveIfAbsent(UserNotification notification) {
        return repository.insertIfAbsent(notification) == 1;
    }
}
