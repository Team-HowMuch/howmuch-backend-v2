package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity.TreasurerAnswerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TreasurerAnswerJpaRepository extends JpaRepository<TreasurerAnswerJpaEntity, UUID> {
    Optional<TreasurerAnswerJpaEntity> findByIdAndUserId(UUID id, Long userId);
}
