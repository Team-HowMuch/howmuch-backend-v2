package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.mapper.AccountMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.common.PostgresLockTimeoutConfigurer;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.external.repository.ExternalConnectionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.user.UserJpaRepository;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountPreferenceStorePort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.DeleteAccountPort;
import com.amateur.howmuchbackend.domain.account.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements
        LoadAccountPort,
        SaveAccountPort,
        DeleteAccountPort,
        AccountPreferenceStorePort {
    private final AccountJpaRepository accountRepository;
    private final UserJpaRepository userRepository;
    private final ExternalConnectionJpaRepository connectionRepository;
    private final CodefSecretCipher cipher;
    private final PostgresLockTimeoutConfigurer lockTimeoutConfigurer;

    @Override
    @Transactional(readOnly = true)
    public Set<String> findCandidateKeys(Long connectionId) {
        return new LinkedHashSet<>(accountRepository.findActiveCandidateKeys(connectionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> findActiveByUserId(Long userId) {
        return accountRepository.findByUser_IdAndActiveTrue(userId).stream()
                .map(AccountMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<Account> findActiveByUserIdForUpdate(Long userId) {
        lockTimeoutConfigurer.apply();
        return accountRepository.findActiveByUserIdForUpdate(userId).stream()
                .map(AccountMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findActiveByIdAndUserId(Long accountId, Long userId) {
        return accountRepository.findByIdAndUser_IdAndActiveTrue(accountId, userId)
                .map(this::toDomainWithAccountKey);
    }

    @Override
    @Transactional
    public Optional<Account> findActiveByIdAndUserIdForUpdate(Long accountId, Long userId) {
        lockTimeoutConfigurer.apply();
        return accountRepository.findActiveByIdAndUserIdForUpdate(accountId, userId)
                .map(this::toDomainWithAccountKey);
    }

    @Override
    @Transactional
    public void delete(Long accountId, Long nextDefaultAccountId) {
        accountRepository.deleteById(accountId);
        accountRepository.flush();
        if (nextDefaultAccountId != null) {
            accountRepository.markDefault(nextDefaultAccountId);
        }
    }

    @Override
    @Transactional
    public void setDefault(Long userId, Long accountId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        accountRepository.clearDefault(userId, now);
        accountRepository.setDefault(userId, accountId, now);
    }

    @Override
    @Transactional
    public void updateDisplayOrders(Long userId, List<Long> orderedAccountIds) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int displayOrder = 0; displayOrder < orderedAccountIds.size(); displayOrder++) {
            accountRepository.updateDisplayOrder(
                    userId,
                    orderedAccountIds.get(displayOrder),
                    displayOrder,
                    now
            );
        }
    }

    @Override
    @Transactional
    public List<Account> saveAll(List<Account> accounts) {
        List<AccountJpaEntity> entities = new ArrayList<>();
        for (Account account : accounts) {
            String ciphertext = cipher.encryptAccountKey(
                    account.userId(), account.externalConnectionId(), account.externalAccountKey()
            );
            AccountJpaEntity entity = accountRepository
                    .findByExternalConnection_IdAndCandidateKey(
                            account.externalConnectionId(), account.candidateKey()
                    )
                    .orElseGet(() -> new AccountJpaEntity(
                            userRepository.getReferenceById(account.userId()),
                            connectionRepository.getReferenceById(account.externalConnectionId()),
                            account.organization(),
                            account.candidateKey()
                    ));
            entity.update(
                    ciphertext,
                    account.type(),
                    account.name(),
                    account.maskedNumber(),
                    account.balance(),
                    account.currency(),
                    account.productCode(),
                    account.isDefault(),
                    account.displayOrder()
            );
            entities.add(entity);
        }
        return accountRepository.saveAll(entities).stream().map(AccountMapper::toDomain).toList();
    }

    @Override
    @Transactional
    public int deactivateAccountsForInactiveConnections() {
        return accountRepository.deactivateForInactiveConnections(LocalDateTime.now(ZoneOffset.UTC));
    }

    private Account toDomainWithAccountKey(AccountJpaEntity entity) {
        return AccountMapper.toDomain(entity, cipher.decryptAccountKey(
                entity.getUser().getId(),
                entity.getExternalConnection().getId(),
                entity.getExternalAccountKeyCiphertext()
        ));
    }
}
