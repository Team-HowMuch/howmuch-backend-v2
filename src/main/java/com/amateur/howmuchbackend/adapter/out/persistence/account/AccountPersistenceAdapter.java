package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.mapper.AccountMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.external.repository.ExternalConnectionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.user.UserJpaRepository;
import com.amateur.howmuchbackend.application.port.out.account.AccountStorePort;
import com.amateur.howmuchbackend.domain.account.Account;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AccountPersistenceAdapter implements AccountStorePort {
    private final AccountJpaRepository accountRepository;
    private final UserJpaRepository userRepository;
    private final ExternalConnectionJpaRepository connectionRepository;
    private final CodefSecretCipher cipher;

    @Override
    @Transactional(readOnly = true)
    public Set<String> findCandidateKeys(Long connectionId) {
        return new LinkedHashSet<>(accountRepository.findActiveCandidateKeys(connectionId));
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
                    account.currency()
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
}
