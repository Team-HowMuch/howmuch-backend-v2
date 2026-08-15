package com.amateur.howmuchbackend.adapter.out.persistence.auth;

import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AuthAuditPersistenceAdapter implements AuthAuditPort {
    private final AuthAuditJpaRepository repository;

    @Override
    public void recordLogout(Long userId, LocalDateTime occurredAt, String clientIp, String userAgent) {
        repository.save(new AuthAuditJpaEntity(userId, occurredAt, clientIp, userAgent));
    }
}
