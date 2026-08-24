package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementReceivingAccountSnapshotJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementRequestMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementReceivingAccountSnapshotJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementTransferInstructionPersistenceAdapter implements
        LoadSettlementSharePort,
        LoadSettlementReceivingAccountSnapshotPort {
    private final SettlementShareJpaRepository shareRepository;
    private final SettlementReceivingAccountSnapshotJpaRepository snapshotRepository;
    private final AccountJpaRepository accountRepository;
    private final CodefSecretCipher cipher;

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementShare> findBySettlementIdAndPayerParticipantId(
            UUID settlementId,
            UUID payerParticipantId
    ) {
        return shareRepository.findBySettlementIdAndPayerParticipantId(
                        settlementId,
                        payerParticipantId
                )
                .map(SettlementRequestMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementReceivingAccountSnapshot> findBySettlementId(
            UUID settlementId
    ) {
        return snapshotRepository.findById(settlementId)
                .flatMap(this::toDomain);
    }

    private Optional<SettlementReceivingAccountSnapshot> toDomain(
            SettlementReceivingAccountSnapshotJpaEntity entity
    ) {
        String ciphertext = entity.getAccountNumberCiphertext();
        if (cipher.isSettlementAccountNumberCiphertext(ciphertext)) {
            return Optional.of(SettlementRequestMapper.toDomain(
                    entity,
                    cipher.decryptSettlementAccountNumber(
                            entity.getSettlementId(),
                            ciphertext
                    )
            ));
        }
        return accountRepository.findById(entity.getSourceAccountId())
                .map(account -> SettlementRequestMapper.toDomain(
                        entity,
                        decryptLegacyAccountNumber(account, ciphertext)
                ));
    }

    private String decryptLegacyAccountNumber(
            AccountJpaEntity account,
            String ciphertext
    ) {
        return cipher.decryptAccountKey(
                account.getUser().getId(),
                account.getExternalConnection().getId(),
                ciphertext
        );
    }
}
