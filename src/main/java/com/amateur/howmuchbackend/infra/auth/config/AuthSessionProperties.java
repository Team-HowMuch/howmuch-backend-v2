package com.amateur.howmuchbackend.infra.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.auth.session")
public record AuthSessionProperties(
        boolean singleSessionPerUser,
        boolean rotateRequestToken
) {
}
