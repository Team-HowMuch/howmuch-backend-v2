package com.amateur.howmuchbackend.infra.solapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.auth.sms.solapi")
public record SolapiSmsSenderProperties(
        String apiKey,
        String apiSecret,
        String sender
) {
}
