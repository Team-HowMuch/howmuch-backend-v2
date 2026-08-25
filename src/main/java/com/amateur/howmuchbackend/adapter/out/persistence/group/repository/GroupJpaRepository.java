package com.amateur.howmuchbackend.adapter.out.persistence.group.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.group.entity.GroupJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupJpaRepository extends JpaRepository<GroupJpaEntity, Long> {
    boolean existsByInviteCode(String inviteCode);
}
