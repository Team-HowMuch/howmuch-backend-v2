package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementNotificationOutboxJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementNotificationOutboxMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementNotificationOutboxJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SettlementNotificationOutboxPersistenceAdapter
        implements SettlementNotificationOutboxPort {
    private final SettlementNotificationOutboxJpaRepository repository;

    @Override
    @Transactional
    public boolean deferProcessing(SettlementNotificationOutbox event, LocalDateTime resumeAt, LocalDateTime now) {
        if (!resumeAt.isAfter(now)) throw new IllegalArgumentException("Deferred delivery must resume in the future.");
        return repository.deferProcessing(event.id(), event.attemptCount(), resumeAt, now) == 1;
    }

    @Override
    @Transactional
    public void saveAll(List<SettlementNotificationOutbox> events) {
        if (events.isEmpty()) {
            return;
        }
        repository.saveAllAndFlush(events.stream()
                .map(SettlementNotificationOutboxMapper::toEntity)
                .toList());
    }

    @Override
    @Transactional
    public Optional<SettlementNotificationOutbox> claimNextReady(LocalDateTime now) {
        Optional<SettlementNotificationOutboxJpaEntity> candidate =
                repository.findNextReadyForUpdate(now);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        SettlementNotificationOutboxJpaEntity entity = candidate.get();
        SettlementNotificationOutbox claimed = SettlementNotificationOutboxMapper
                .toDomain(entity)
                .claim(now);
        entity.apply(claimed);
        repository.flush();
        return Optional.of(claimed);
    }

    @Override
    @Transactional
    public int recoverStale(
            int batchSize,
            LocalDateTime staleBefore,
            LocalDateTime now,
            int maxAttempts
    ) {
        requirePositiveBatchSize(batchSize);
        List<SettlementNotificationOutboxJpaEntity> entities =
                repository.findStaleForUpdate(staleBefore, batchSize);
        for (SettlementNotificationOutboxJpaEntity entity : entities) {
            SettlementNotificationOutbox recovered = SettlementNotificationOutboxMapper
                    .toDomain(entity)
                    .recover(maxAttempts, now);
            entity.apply(recovered);
            if (recovered.status() == com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutboxStatus.RETRY)
                entity.resetPublicationForRetry(recovered.nextAttemptAt());
        }
        repository.flush();
        return entities.size();
    }

    @Override
    @Transactional
    public boolean transitionFromProcessing(
            SettlementNotificationOutbox event,
            LocalDateTime transitionedAt
    ) {
        return switch (event.status()) {
            case SUCCEEDED -> repository.markSucceeded(
                    event.id(), event.attemptCount(), event.completedAt()
            ) == 1;
            case RETRY -> repository.markRetry(
                    event.id(),
                    event.attemptCount(),
                    event.nextAttemptAt(),
                    event.lastFailureCode().name(),
                    transitionedAt
            ) == 1;
            case FAILED -> repository.markFailed(
                    event.id(),
                    event.attemptCount(),
                    event.lastFailureCode().name(),
                    event.completedAt()
            ) == 1;
            case PENDING, PROCESSING -> throw new IllegalArgumentException(
                    "처리 결과 상태만 저장할 수 있습니다: " + event.status()
            );
        };
    }

    private void requirePositiveBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("알림 Outbox batch 크기는 1 이상이어야 합니다.");
        }
    }
}
