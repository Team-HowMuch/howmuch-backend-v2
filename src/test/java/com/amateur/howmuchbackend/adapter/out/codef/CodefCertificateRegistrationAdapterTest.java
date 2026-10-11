package com.amateur.howmuchbackend.adapter.out.codef;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.SubmitCertificateUploadWebRequest;
import com.amateur.howmuchbackend.adapter.out.security.CertificateSecretProtectionAdapter;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.application.certificate.model.CertificateMaterial;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.codef.config.CodefProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import software.amazon.awssdk.services.kms.KmsClient;

import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.*;

import javax.crypto.Cipher;

@ExtendWith(OutputCaptureExtension.class)
class CodefCertificateRegistrationAdapterTest {
    private final CodefClient client = mock(CodefClient.class);
    private final CodefRsaEncryptor rsa = mock(CodefRsaEncryptor.class);
    private final CodefCertificateRegistrationAdapter adapter =
            new CodefCertificateRegistrationAdapter(client, rsa, properties(true));
    private final ObjectMapper json = new ObjectMapper();

    private static CertificateProperties properties(boolean detailed) {
        return new CertificateProperties(
                true, "", "", "", List.of(), null, null, null, null, null, null, detailed);
    }

    @Test
    void legacyProducerModeIsTheDefaultUntilAllConsumersAreUpgraded() {
        var source =
                new org.springframework.boot.context.properties.source
                        .MapConfigurationPropertySource(
                        Map.of(
                                "howmuch.certificate.enabled",
                                "true",
                                "howmuch.certificate.detailed-errors-enabled",
                                "true"));
        var bound =
                new org.springframework.boot.context.properties.bind.Binder(source)
                        .bind(
                                "howmuch.certificate",
                                org.springframework.boot.context.properties.bind.Bindable.of(
                                        CertificateProperties.class))
                        .get();
        assertThat(bound.detailedErrorsEnabled()).isTrue();
        var oldMode =
                new CertificateProperties(
                        true, "", "", "", List.of(), null, null, null, null, null, null);
        assertThat(oldMode.detailedErrorsEnabled()).isFalse();
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-04000"},"data":{"errorList":[{"organization":"0020","code":"CF-12302"}]}}
"""));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            assertThat(
                            new CodefCertificateRegistrationAdapter(client, rsa, oldMode)
                                    .register("0020", material, false)
                                    .failureCode())
                    .isEqualTo("REGISTRATION_REJECTED");
        }
    }

    @Test
    void certificatePayloadUsesLoginTypeZeroAndRsaPassword() throws Exception {
        byte[] expectedDer = new byte[] {48, (byte) 130, 1, 2, 3};
        byte[] expectedKey = new byte[2048];
        for (int i = 0; i < expectedKey.length; i++) expectedKey[i] = (byte) (i * 17 + 3);
        String password = " synthetic +한글! ";
        var command =
                new SubmitCertificateUploadWebRequest(
                                Base64.getEncoder().encodeToString(expectedDer),
                                Base64.getEncoder().encodeToString(expectedKey),
                                password,
                                "123456",
                                "1234",
                                "19900101")
                        .toCommand(UUID.randomUUID(), "pc-fixture", "upload-fixture");
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        var protection =
                new CertificateSecretProtectionAdapter(
                        new CertificateProperties(
                                true,
                                "",
                                Base64.getEncoder().encodeToString(new byte[32]),
                                Base64.getEncoder().encodeToString(new byte[32]),
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        new StaticListableBeanFactory().getBeanProvider(KmsClient.class),
                        env);
        UUID uploadId = UUID.randomUUID();
        CertificateEnvelope sealed;
        try (var original =
                new CertificateMaterial(
                        command.der(),
                        command.key(),
                        command.password(),
                        command.accountNumber(),
                        command.accountPassword(),
                        command.birthDate())) {
            sealed = protection.sealUpload(command.sessionId(), uploadId, original);
        }
        // The original material and openUpload's temporary arrays have been cleared now.
        var pairGenerator = KeyPairGenerator.getInstance("RSA");
        pairGenerator.initialize(2048);
        var pair = pairGenerator.generateKeyPair();
        var actualRsa =
                new CodefRsaEncryptor(
                        new CodefProperties(
                                null,
                                null,
                                "",
                                "",
                                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                                "",
                                null,
                                null));
        var decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        decrypt.init(Cipher.DECRYPT_MODE, pair.getPrivate());
        var builder = RestClient.builder().baseUrl("https://codef.example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(CodefTokenClient.class);
        when(tokens.getAccessToken()).thenReturn("synthetic-token");
        server.expect(requestTo("https://codef.example.test/v1/account/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        request -> {
                            var body = ((MockClientHttpRequest) request).getBodyAsString();
                            var account =
                                    json.readTree(URLDecoder.decode(body, StandardCharsets.UTF_8))
                                            .at("/accountList/0");
                            assertThat(account.path("loginType").asText()).isEqualTo("0");
                            assertThat(account.path("certType").asText()).isEqualTo("1");
                            assertThat(Base64.getDecoder().decode(account.path("derFile").asText()))
                                    .containsExactly(expectedDer);
                            assertThat(Base64.getDecoder().decode(account.path("keyFile").asText()))
                                    .containsExactly(expectedKey);
                            assertThat(account.path("withdrawAccountNo").asText())
                                    .isEqualTo("123456");
                            try {
                                assertThat(
                                                new String(
                                                        decrypt.doFinal(
                                                                Base64.getDecoder()
                                                                        .decode(
                                                                                account.path(
                                                                                                "password")
                                                                                        .asText())),
                                                        StandardCharsets.UTF_8))
                                        .isEqualTo(password);
                                assertThat(
                                                new String(
                                                        decrypt.doFinal(
                                                                Base64.getDecoder()
                                                                        .decode(
                                                                                account.path(
                                                                                                "withdrawAccountPassword")
                                                                                        .asText())),
                                                        StandardCharsets.UTF_8))
                                        .isEqualTo("1234");
                            } catch (java.security.GeneralSecurityException failure) {
                                throw new AssertionError(
                                        "Synthetic RSA round trip failed", failure);
                            }
                        })
                .andRespond(
                        withSuccess(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"fixture","successList":[{"organization":"0020"}]}}
""",
                                MediaType.APPLICATION_JSON));
        var actualAdapter =
                new CodefCertificateRegistrationAdapter(
                        new CodefClient(builder.build(), tokens, json),
                        actualRsa,
                        properties(true));
        try (var restored = protection.openUpload(command.sessionId(), uploadId, sealed)) {
            assertThat(restored.key()).containsExactly(expectedKey);
            assertThat(actualAdapter.register("0020", restored, true).connectedId())
                    .isEqualTo("fixture");
            // Provider clears only its copies; the worker retains its own material until close().
            assertThat(restored.key()).containsExactly(expectedKey);
        }
        server.verify();
        verify(tokens, times(1)).getAccessToken();
    }

    @Test
    void timeoutIsUncertainAndIsNeverRetried() {
        when(client.postOnce(anyString(), any()))
                .thenThrow(new RuntimeException("raw-secret-must-not-escape"));
        try (var m =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "secret", null, null, null)) {
            assertThatThrownBy(() -> adapter.register("0020", m, true))
                    .isInstanceOf(
                            CodefCertificateRegistrationAdapter
                                    .CertificateProviderUncertainException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("raw-secret");
            verify(client, times(1)).postOnce(anyString(), any());
        }
    }

    @Test
    void explicitRejectionWithoutConnectionIsFinal(CapturedOutput output) {
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
                                {"result":{"code":"CF-04001"},"data":{}}
                                """));
        try (var m =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "secret", null, null, null)) {
            assertThat(adapter.register("0020", m, true).failureCode()).isEqualTo("CF-04001");
            assertThat(output.getAll())
                    .contains(
                            "resultCode=CF-04001",
                            "derBytes=1",
                            "keyBytes=1",
                            "derAllZero=false",
                            "keyAllZero=false");
        }
        try (var zeroed =
                new CertificateMaterial(
                        new byte[] {1}, new byte[2048], "synthetic-password", null, null, null)) {
            assertThat(adapter.register("0020", zeroed, false).failureCode()).isEqualTo("CF-04001");
            assertThat(output.getAll())
                    .contains("keyBytes=2048", "keyAllZero=true")
                    .doesNotContain("synthetic-password");
        }
    }

    @Test
    void institutionFailureTakesPriorityOverGenericOuterFailure(CapturedOutput output) {
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-04000","message":"raw-provider-secret"},"data":{"errorList":[
  {"organization":"0004","code":"CF-13031"},
  {"organization":"0020","code":"CF-12302","message":"raw-password-secret"}]}}
"""));
        try (var m =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            assertThat(adapter.register("0020", m, false).failureCode()).isEqualTo("CF-12302");
            verify(client, times(1)).postOnce(anyString(), any());
            assertThat(output.getAll())
                    .doesNotContain(
                            "raw-provider-secret", "raw-password-secret", "synthetic-password");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"result\":{\"code\":\"CF-04000\"},\"data\":{\"connectedId\":\"created\",\"errorList\":[{\"code\":\"CF-12302\"}]}}",
                "{\"result\":{\"code\":\"CF-04000\"},\"data\":{\"successList\":[{\"organization\":\"0004\"}],\"errorList\":[{\"code\":\"CF-12302\"}]}}",
                "{\"result\":{\"code\":\"CF-04012\"},\"data\":{\"errorList\":[{\"code\":\"CF-12302\"}]}}",
                "{\"result\":{\"code\":\"CF-04034\"}}",
                "{\"result\":{\"code\":\"CF-00016\"}}",
                "{\"result\":{\"code\":\"raw-secret\"}}",
                "{\"result\":{\"code\":\"CF-00000\"}}"
            })
    void ambiguousOrMalformedResponsesRemainUncertain(String body) {
        when(client.postOnce(anyString(), any())).thenReturn(json.readTree(body));
        try (var m =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            assertThatThrownBy(() -> adapter.register("0020", m, false))
                    .isInstanceOf(
                            CodefCertificateRegistrationAdapter
                                    .CertificateProviderUncertainException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("raw-secret");
            verify(client, times(1)).postOnce(anyString(), any());
        }
    }

    @Test
    void httpErrorLogsOnlyStatusAndProviderCodeWithoutRetry(CapturedOutput output) {
        String body = "{\"result\":{\"code\":\"CF-04001\",\"message\":\"raw-private-response\"}}";
        when(client.postOnce(anyString(), any()))
                .thenThrow(
                        new CodefClient.CodefHttpException(
                                400, body, new RuntimeException("raw-private-cause")));
        when(client.decode(body)).thenReturn(json.readTree(body));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "raw-private-password", null, null, null)) {
            assertThatThrownBy(() -> adapter.register("0020", material, false))
                    .isInstanceOf(
                            CodefCertificateRegistrationAdapter
                                    .CertificateProviderUncertainException.class)
                    .hasNoCause();
            assertThat(output.getAll())
                    .contains("httpStatus=400", "providerCode=CF-04001")
                    .doesNotContain("raw-private");
            verify(client, times(1)).postOnce(anyString(), any());
        }
    }

    @Test
    void preparationFailureLogsTypeWithoutCredentialsOrSending(CapturedOutput output) {
        when(rsa.encrypt(anyString())).thenThrow(new IllegalArgumentException("raw-private-key"));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "raw-private-password", null, null, null)) {
            assertThatThrownBy(() -> adapter.register("0020", material, false))
                    .isInstanceOf(
                            CodefCertificateRegistrationAdapter
                                    .CertificateProviderUncertainException.class)
                    .hasNoCause();
            assertThat(output.getAll())
                    .contains("failureType=IllegalArgumentException")
                    .doesNotContain("raw-private");
            verifyNoInteractions(client);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "update"})
    void certificateChangeUsesExistingConnectedIdAndAcceptsMissingResponseId(String operation) {
        when(rsa.encrypt(anyString())).thenReturn("synthetic-encrypted");
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                "{\"result\":{\"code\":\"CF-00000\"},\"data\":{\"successList\":[{\"organization\":\"0020\",\"code\":\"CF-00000\"}],\"errorList\":[]}}"));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            var result =
                    operation.equals("add")
                            ? adapter.add("existing-fixture", "0020", material, false)
                            : adapter.update("existing-fixture", "0020", material, false);
            assertThat(result.connectedId()).isEqualTo("existing-fixture");
        }
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).postOnce(eq("/v1/account/" + operation), captor.capture());
        assertThat(captor.getValue().get("connectedId")).isEqualTo("existing-fixture");
        assertThat(
                        ((Map) ((List) captor.getValue().get("accountList")).getFirst())
                                .get("loginType"))
                .isEqualTo("0");
        verifyNoMoreInteractions(client);
    }

    @Test
    void responseCannotReplaceExistingConnectedId() {
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                "{\"result\":{\"code\":\"CF-00000\"},\"data\":{\"connectedId\":\"unexpected-fixture\",\"successList\":[{\"organization\":\"0020\"}]}}"));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            assertThatThrownBy(() -> adapter.update("existing-fixture", "0020", material, false))
                    .isInstanceOf(
                            CodefCertificateRegistrationAdapter
                                    .CertificateProviderUncertainException.class);
        }
        verify(client, times(1)).postOnce(anyString(), any());
    }

    @Test
    void existingIdInRejectedUpdateDoesNotHidePasswordError() {
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                "{\"result\":{\"code\":\"CF-04000\"},\"data\":{\"connectedId\":\"existing-fixture\",\"errorList\":[{\"organization\":\"0020\",\"code\":\"CF-12302\"}]}}"));
        try (var material =
                new CertificateMaterial(
                        new byte[] {1}, new byte[] {2}, "synthetic-password", null, null, null)) {
            assertThat(adapter.update("existing-fixture", "0020", material, false).failureCode())
                    .isEqualTo("CF-12302");
        }
    }
}
