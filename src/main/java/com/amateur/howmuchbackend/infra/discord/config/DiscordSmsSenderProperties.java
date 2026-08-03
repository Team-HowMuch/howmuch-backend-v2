package com.amateur.howmuchbackend.infra.discord.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.auth.sms.discord")
public record DiscordSmsSenderProperties(
        String webhookUrl,
        String username
) {
    public DiscordSmsSenderProperties {
        if (username == null || username.isBlank()) {
            username = "HowMuch SMS Mock";
        }
    }

    public boolean hasWebhookUrl() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
