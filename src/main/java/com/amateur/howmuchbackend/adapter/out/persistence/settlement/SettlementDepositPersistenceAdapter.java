package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDepositJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementDepositJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
    private final CodefSecretCipher cipher;

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
    public List<ConfirmedShareAmount> findConfirmedShareAmountsBySettlementId(UUID settlementId) {
        return repository.findConfirmedShareAmountsBySettlementId(settlementId)
                .stream()
                .map(projection -> {
                    try {
                        return new ConfirmedShareAmount(
                                projection.getShareId(),
                                projection.getAmount().longValueExact()
                        );
                    } catch (ArithmeticException exception) {
                        throw ApplicationException.of(
                                SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND,
                                exception
                        );
                    }
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findMatchedExternalTransactionKeysByReceivingAccount(
            Long creatorId,
            Long sourceAccountId,
            String organization,
            String accountNumber,
            Collection<String> externalTransactionKeys
    ) {
        if (externalTransactionKeys.isEmpty()) {
            return Set.of();
        }
        return repository.findMatchedTransactionsByCreatorIdAndOrganization(
                        creatorId,
                        organization,
                        externalTransactionKeys
                )
                .stream()
                .filter(candidate -> sameReceivingAccount(
                        sourceAccountId,
                        accountNumber,
                        candidate.getSettlementId(),
                        candidate.getSourceAccountId(),
                        candidate.getAccountNumberCiphertext()
                ))
                .map(SettlementDepositJpaRepository.MatchedTransactionProjection::getExternalTransactionKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayableDepositCandidate> findUnfinishedPayableCandidatesByReceivingAccount(
            Long creatorId,
            Long sourceAccountId,
            String organization,
            String accountNumber
    ) {
        return repository.findUnfinishedPayableCandidatesByCreatorIdAndOrganization(
                        creatorId,
                        organization
                )
                .stream()
                .filter(candidate -> sameReceivingAccount(
                        sourceAccountId,
                        accountNumber,
                        candidate.getSettlementId(),
                        candidate.getSourceAccountId(),
                        candidate.getAccountNumberCiphertext()
                ))
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

    private boolean sameReceivingAccount(
            Long sourceAccountId,
            String accountNumber,
            UUID candidateSettlementId,
            Long candidateSourceAccountId,
            String candidateAccountNumberCiphertext
    ) {
        if (sourceAccountId.equals(candidateSourceAccountId)) {
            return true;
        }
        return cipher.isSettlementAccountNumberCiphertext(candidateAccountNumberCiphertext)
                && accountNumber.equals(cipher.decryptSettlementAccountNumber(
                        candidateSettlementId,
                        candidateAccountNumberCiphertext
                ));
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
