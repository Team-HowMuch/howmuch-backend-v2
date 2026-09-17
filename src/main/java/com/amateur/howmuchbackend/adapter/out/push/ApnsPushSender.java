package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort.SendResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.infra.notification.config.ApnsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class ApnsPushSender {
    private static final String ENDPOINT = "https://api.sandbox.push.apple.com/3/device/";
    private static final String TOPIC = "com.teamhowmuch.app";

    private final ApnsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private String cachedProviderToken;
    private Instant providerTokenIssuedAt;

    @Autowired
    public ApnsPushSender(ApnsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    ApnsPushSender(ApnsProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    SendResult send(String deviceToken, String title, String body, Map<String, String> data) {
        if (isBlank(properties.keyId()) || isBlank(properties.teamId()) || isBlank(properties.privateKeyPath())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put("aps", Map.of("alert", Map.of("title", title, "body", body), "sound", "default"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + deviceToken))
                    .version(HttpClient.Version.HTTP_2)
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "bearer " + providerToken())
                    .header("apns-topic", TOPIC)
                    .header("apns-push-type", "alert")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return SendResult.SENT;
            }
            String reason = responseReason(response.body());
            log.warn("APNs push rejected. status={}, reason={}, apnsId={}", response.statusCode(), reason,
                    response.headers().firstValue("apns-id").orElse("unknown"));
            if (reason.equals("BadDeviceToken") || reason.equals("DeviceTokenNotForTopic")
                    || reason.equals("Unregistered")) {
                return SendResult.INVALID_TARGET;
            }
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED, exception);
        } catch (Exception exception) {
            if (exception instanceof ApplicationException applicationException) {
                throw applicationException;
            }
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED, exception);
        }
    }

    private synchronized String providerToken() throws Exception {
        Instant now = Instant.now();
        if (cachedProviderToken != null && providerTokenIssuedAt.plus(Duration.ofMinutes(50)).isAfter(now)) {
            return cachedProviderToken;
        }
        String pem = Files.readString(Path.of(properties.privateKeyPath()));
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        cachedProviderToken = Jwts.builder()
                .header().keyId(properties.keyId()).and()
                .issuer(properties.teamId())
                .issuedAt(Date.from(now))
                .signWith(key, Jwts.SIG.ES256)
                .compact();
        providerTokenIssuedAt = now;
        return cachedProviderToken;
    }

    private String responseReason(String body) {
        try {
            var json = objectMapper.readTree(body);
            String reason = json == null ? "Unknown" : json.path("reason").asText("Unknown");
            return reason.matches("[A-Za-z]{1,80}") ? reason : "Unknown";
        } catch (IOException exception) {
            return "Unknown";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
