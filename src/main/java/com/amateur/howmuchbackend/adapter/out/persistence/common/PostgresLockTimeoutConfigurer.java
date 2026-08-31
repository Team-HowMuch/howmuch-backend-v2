package com.amateur.howmuchbackend.adapter.out.persistence.common;

import com.amateur.howmuchbackend.infra.persistence.config.PersistenceConcurrencyProperties;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PostgresLockTimeoutConfigurer {
    private final EntityManager entityManager;
    private final PersistenceConcurrencyProperties properties;

    public void apply() {
        String timeout = properties.lockTimeout().toMillis() + "ms";
        entityManager.createNativeQuery(
                        "select set_config('lock_timeout', :timeout, true)",
                        String.class
                )
                .setParameter("timeout", timeout)
                .getSingleResult();
    }
}
