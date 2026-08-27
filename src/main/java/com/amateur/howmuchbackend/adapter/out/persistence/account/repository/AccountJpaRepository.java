package com.amateur.howmuchbackend.adapter.out.persistence.account.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {
    @Query("""
            select account.candidateKey
              from AccountJpaEntity account
             where account.externalConnection.id = :connectionId
               and account.active = true
            """)
    List<String> findActiveCandidateKeys(@Param("connectionId") Long connectionId);

    Optional<AccountJpaEntity> findByExternalConnection_IdAndCandidateKey(Long connectionId, String candidateKey);

    List<AccountJpaEntity> findByUser_IdAndActiveTrue(Long userId);

    Optional<AccountJpaEntity> findByIdAndUser_IdAndActiveTrue(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
              from AccountJpaEntity account
             where account.id = :id
               and account.user.id = :userId
               and account.active = true
            """)
    Optional<AccountJpaEntity> findActiveByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") Long userId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AccountJpaEntity account
               set account.isDefault = true
             where account.id = :accountId
               and account.active = true
            """)
    int markDefault(@Param("accountId") Long accountId);

    @Modifying(clearAutomatically = true)
    @Query("""
            update AccountJpaEntity account
               set account.active = false,
                   account.updatedAt = :updatedAt
             where account.active = true
               and account.externalConnection.active = false
            """)
    int deactivateForInactiveConnections(@Param("updatedAt") LocalDateTime updatedAt);
}
