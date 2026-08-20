package com.amateur.howmuchbackend.adapter.out.persistence.external.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ExternalConnectionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ExternalConnectionJpaRepository extends JpaRepository<ExternalConnectionJpaEntity, Long> {
    Optional<ExternalConnectionJpaEntity> findByUser_Id(Long userId);

    Optional<ExternalConnectionJpaEntity> findByUser_IdAndActiveTrueAndConfigurationFingerprint(
            Long userId,
            String configurationFingerprint
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ExternalConnectionJpaEntity> findByIdAndUser_IdAndActiveTrueAndConfigurationFingerprint(
            Long id,
            Long userId,
            String configurationFingerprint
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            update ExternalConnectionJpaEntity connection
               set connection.active = false,
                   connection.updatedAt = :updatedAt
             where connection.active = true
               and connection.configurationFingerprint <> :configurationFingerprint
            """)
    int deactivateIncompatible(
            @Param("configurationFingerprint") String configurationFingerprint,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
