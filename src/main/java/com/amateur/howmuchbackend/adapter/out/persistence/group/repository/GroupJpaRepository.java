package com.amateur.howmuchbackend.adapter.out.persistence.group.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GroupJpaRepository extends JpaRepository<GroupJpaEntity, Long> {
    boolean existsByInviteCode(String inviteCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select targetGroup from GroupJpaEntity targetGroup where targetGroup.id = :groupId")
    Optional<GroupJpaEntity> findByIdForUpdate(@Param("groupId") Long groupId);
}
