package com.amateur.howmuchbackend.adapter.out.redis.certificate;

import com.amateur.howmuchbackend.application.port.out.certificate.CertificateRequestLimitPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRequestLimitAdapter implements CertificateRequestLimitPort {
    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 then"
                            + " redis.call('EXPIRE',KEYS[1],600) end; return n",
                    Long.class);
    private final StringRedisTemplate redis;

    public CertificateRequestLimitAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean acquire(String hashedAddress) {
        Long n = redis.execute(SCRIPT, List.of("certificate:create:" + hashedAddress));
        return n != null && n <= 10;
    }
}
