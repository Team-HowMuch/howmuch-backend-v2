package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementRequestMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementReceivingAccountSnapshotJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementRequestPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementRequestPersistenceAdapter implements SaveSettlementRequestPort {
    private final SettlementReceivingAccountSnapshotJpaRepository snapshotRepository;
    private final SettlementShareJpaRepository shareRepository;
    private final SettlementLinkJpaRepository linkRepository;
    private final AccountJpaRepository accountRepository;
    private final CodefSecretCipher cipher;

    @Override
    public void save(
            SettlementReceivingAccountSnapshot accountSnapshot,
            List<SettlementShare> shares,
            SettlementLink link
    ) {
        accountRepository.findById(accountSnapshot.sourceAccountId())
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        String ciphertext = cipher.encryptSettlementAccountNumber(
                accountSnapshot.settlementId(),
                accountSnapshot.accountNumber()
        );
        snapshotRepository.saveAndFlush(
                SettlementRequestMapper.toEntity(accountSnapshot, ciphertext)
        );
        if (!shares.isEmpty()) {
            shareRepository.saveAllAndFlush(shares.stream()
                    .map(SettlementRequestMapper::toEntity)
                    .toList());
        }
        linkRepository.saveAndFlush(SettlementRequestMapper.toEntity(link));
    }
}
