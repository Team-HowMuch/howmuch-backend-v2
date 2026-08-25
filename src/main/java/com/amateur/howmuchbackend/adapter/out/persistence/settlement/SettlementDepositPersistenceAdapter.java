package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDepositJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementDepositJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementDepositPersistenceAdapter implements SettlementDepositStorePort {
    private final SettlementDepositJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<SettlementDeposit> findAllBySettlementId(UUID settlementId) {
        return repository.findAllBySettlementIdOrderByMatchedAtAscIdAsc(settlementId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findMatchedTransactionIds(Collection<Long> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Set.of();
        }
        return repository.findAllByAccountTransactionIdIn(transactionIds)
                .stream()
                .map(SettlementDepositJpaEntity::getAccountTransactionId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayableDepositCandidate> findUnfinishedPayableCandidatesBySourceAccountId(
            Long sourceAccountId
    ) {
        return repository.findUnfinishedPayableCandidatesBySourceAccountId(sourceAccountId)
                .stream()
                .map(candidate -> new PayableDepositCandidate(
                        candidate.getSettlementId(),
                        candidate.getShareId(),
                        candidate.getPayerName(),
                        candidate.getRemainingAmount().longValueExact(),
                        candidate.getSnapshotCreatedAt()
                                .atOffset(ZoneOffset.UTC)
                                .toLocalDateTime()
                ))
                .toList();
    }

    @Override
    @Transactional
    public void saveAll(List<SettlementDeposit> deposits) {
        repository.saveAll(deposits.stream().map(this::toEntity).toList());
    }

    private SettlementDepositJpaEntity toEntity(SettlementDeposit deposit) {
        return new SettlementDepositJpaEntity(
                deposit.id(),
                deposit.settlementId(),
                deposit.shareId(),
                deposit.sourceAccountId(),
                deposit.accountTransactionId(),
                deposit.matchedAt()
        );
    }

    private SettlementDeposit toDomain(SettlementDepositJpaEntity entity) {
        return new SettlementDeposit(
                entity.getId(),
                entity.getSettlementId(),
                entity.getShareId(),
                entity.getSourceAccountId(),
                entity.getAccountTransactionId(),
                entity.getMatchedAt()
        );
    }
}
