package com.amateur.howmuchbackend.infra.discord.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiscordSmsSenderProperties.class)
@ConditionalOnProperty(name = "howmuch.auth.sms.sender", havingValue = "discord")
public class DiscordSenderRestClientConfig {
    @Bean
    @Qualifier("discordSenderRestClient")
    RestClient discordSenderRestClient(
            RestClient.Builder builder,
            DiscordSmsSenderProperties properties
    ) {
        if (!properties.hasWebhookUrl()) {
            throw new IllegalStateException("Discord SMS webhook URL must be configured.");
        }
        return builder.clone().baseUrl(properties.webhookUrl()).build();
    }
}
