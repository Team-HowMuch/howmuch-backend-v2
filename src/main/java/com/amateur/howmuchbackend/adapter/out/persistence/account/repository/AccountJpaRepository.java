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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
              from AccountJpaEntity account
             where account.user.id = :userId
               and account.active = true
             order by account.id
            """)
    List<AccountJpaEntity> findActiveByUserIdForUpdate(@Param("userId") Long userId);

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET is_default = FALSE,
                   updated_at = :updatedAt
             WHERE user_id = :userId
               AND active = TRUE
               AND is_default = TRUE
            """, nativeQuery = true)
    int clearDefault(
            @Param("userId") Long userId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE accounts
               SET is_default = TRUE,
                   updated_at = :updatedAt
             WHERE id = :accountId
               AND user_id = :userId
               AND active = TRUE
            """, nativeQuery = true)
    int setDefault(
            @Param("userId") Long userId,
            @Param("accountId") Long accountId,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            update AccountJpaEntity account
               set account.displayOrder = :displayOrder,
                   account.updatedAt = :updatedAt
             where account.id = :accountId
               and account.user.id = :userId
               and account.active = true
            """)
    int updateDisplayOrder(
            @Param("userId") Long userId,
            @Param("accountId") Long accountId,
            @Param("displayOrder") int displayOrder,
            @Param("updatedAt") LocalDateTime updatedAt
    );

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
