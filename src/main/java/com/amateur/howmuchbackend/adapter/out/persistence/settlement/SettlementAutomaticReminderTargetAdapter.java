package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementAutomaticReminderTargetPort;
import lombok.RequiredArgsConstructor;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementAutomaticReminderJpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementAutomaticReminderTargetAdapter implements SettlementAutomaticReminderTargetPort {
    private final SettlementAutomaticReminderJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<Target> findDue(LocalDateTime automaticDueBefore, LocalDateTime lastReminderBefore, int limit) {
        return repository.findDue(automaticDueBefore, lastReminderBefore, limit).stream()
                .map(row -> new Target(row.getSettlementId(), row.getParticipantId(), row.getShareId(), row.getRecipientUserId()))
                .toList();
    }

    @Override
    public boolean hasRequestNotification(UUID settlementId, Long recipientUserId) {
        return repository.hasRequestNotification(settlementId, recipientUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> findLatestAutomaticReminderAt(UUID shareId, Long recipientUserId) {
        return repository.findLatestAutomaticReminderAt(shareId, recipientUserId);
    }
}
