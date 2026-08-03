package com.amateur.howmuchbackend.adapter.out.sms;

import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.infra.discord.config.DiscordSmsSenderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "howmuch.auth.sms.sender", havingValue = "discord")
public class DiscordSmsSenderAdapter implements SmsSenderPort {
    @Qualifier("discordSenderRestClient")
    private final RestClient discordSenderRestClient;
    private final DiscordSmsSenderProperties properties;

    @Override
    public void send(String phoneNumber, String verificationCode) {
        discordSenderRestClient.post()
                .uri("")
                .body(new DiscordWebhookRequest(
                        properties.username(),
                        "[SMS MOCK] VerificationCodeIssued\nPhone: `" + mask(phoneNumber)
                                + "`\nCode: `" + verificationCode + "`"
                ))
                .retrieve()
                .toBodilessEntity();
    }

    private String mask(String phoneNumber) {
        if (phoneNumber.length() <= 4) return "****";
        return phoneNumber.substring(0, 3) + "****" + phoneNumber.substring(phoneNumber.length() - 2);
    }

    private record DiscordWebhookRequest(String username, String content) {
    }
}
