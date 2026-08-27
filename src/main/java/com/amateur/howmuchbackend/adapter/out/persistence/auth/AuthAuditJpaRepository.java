package com.amateur.howmuchbackend.adapter.out.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAuditJpaRepository extends JpaRepository<AuthAuditJpaEntity, Long> {
}
