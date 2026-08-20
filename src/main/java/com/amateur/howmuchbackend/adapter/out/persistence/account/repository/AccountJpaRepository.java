package com.amateur.howmuchbackend.adapter.out.persistence.account.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
