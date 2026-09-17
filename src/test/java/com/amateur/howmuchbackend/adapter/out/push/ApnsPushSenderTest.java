package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.infra.notification.config.ApnsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApnsPushSenderTest {
    @TempDir Path tempDir;

    @ParameterizedTest
    @CsvSource({"dev,api.sandbox.push.apple.com", "prod,api.push.apple.com"})
    void sendsToConfiguredEnvironmentAndHandlesApnsResponse(String environment, String host) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var key = generator.generateKeyPair().getPrivate();
        Path keyPath = tempDir.resolve("apns.p8");
        Files.writeString(keyPath, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");

        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400, 200);
        when(response.body()).thenReturn("{\"reason\":\"DeviceTokenNotForTopic\"}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("apns-id", List.of("test-id")), (a, b) -> true));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var sender = new ApnsPushSender(new ApnsProperties("ABC123DEFG", "DEF123GHIJ", keyPath.toString(), environment),
                new ObjectMapper(), client);
        String deviceToken = "ab".repeat(32);

        assertThat(sender.send(deviceToken, "제목", "본문", Map.of("type", "SETTLEMENT_REMINDER")))
                .isEqualTo(SettlementPushSenderPort.SendResult.INVALID_TARGET);
        assertThat(sender.send(deviceToken, "제목", "본문", Map.of("type", "SETTLEMENT_REMINDER")))
                .isEqualTo(SettlementPushSenderPort.SendResult.SENT);
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getAllValues().getFirst().uri().toString())
                .isEqualTo("https://" + host + "/3/device/" + deviceToken);
        assertThat(request.getAllValues().getFirst().headers().firstValue("apns-topic"))
                .hasValue("com.teamhowmuch.app");
        assertThat(request.getAllValues().getFirst().headers().firstValue("authorization").orElseThrow())
                .startsWith("bearer ");
        assertThat(request.getAllValues().getLast().headers().firstValue("authorization"))
                .isEqualTo(request.getAllValues().getFirst().headers().firstValue("authorization"));
        assertThat(request.getAllValues().getFirst().version()).hasValue(HttpClient.Version.HTTP_2);
    }

    @Test
    void rejectsUnknownEnvironment() {
        assertThatThrownBy(() -> new ApnsPushSender(
                new ApnsProperties("key", "team", "path", "unknown"),
                new ObjectMapper(), mock(HttpClient.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("APNS_ENVIRONMENT must be dev or prod");
    }
}
