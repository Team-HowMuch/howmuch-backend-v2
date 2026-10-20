package com.amateur.howmuchbackend.adapter.out.codef;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.*;

import java.util.*;

class CodefSmsContinuationAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CodefClient client = mock(CodefClient.class);
    private final CodefSmsContinuationAdapter adapter =
            new CodefSmsContinuationAdapter(
                    client,
                    json,
                    new com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties(
                            true, "", "", "", List.of(), null, null, null, null, null, null, true));
    private final Map<String, Object> request =
            Map.of(
                    "accountList",
                    List.of(
                            Map.of(
                                    "organization",
                                    "0020",
                                    "loginType",
                                    "0",
                                    "password",
                                    "synthetic-encrypted-password")));

    private JsonNode response(String method, String destination) {
        return json.readTree(
                """
{"result":{"code":"CF-03002"},"data":{"continue2Way":true,"method":"%s",
 "jobIndex":0,"threadIndex":0,"jti":"synthetic-jti","twoWayTimestamp":1791504000000,
 "extraInfo":{"reqSMSAuthNo":"%s"}}}
"""
                        .formatted(method, destination));
    }

    private AdditionalAuthenticationRequest capture(JsonNode response) {
        try {
            adapter.requireIfSupported("/v1/account/create", request, response, "0020");
        } catch (AdditionalAuthenticationRequiredException required) {
            return required.request();
        }
        throw new AssertionError("Expected an explicit SMS continuation");
    }

    @Test
    void resumesOriginalAllowlistedRequestOnceWithSmsAndTwoWayContext() {
        var continuation = capture(response("smsAuthNo", "010-****-0000"));
        var accepted = adapter.acceptSms(continuation.payload(), "012345");
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"synthetic-connected",
 "successList":[{"organization":"0020","code":"CF-00000"}],"errorList":[]}}
"""));
        assertThat(adapter.resume(accepted).connectedId()).isEqualTo("synthetic-connected");
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(client, times(1)).postOnce(eq("/v1/account/create"), payload.capture());
        var body = json.valueToTree(payload.getValue());
        assertThat(body.path("is2Way").asBoolean()).isTrue();
        assertThat(body.at("/twoWayInfo/jti").asText()).isEqualTo("synthetic-jti");
        assertThat(body.path("smsAuthNo").asText()).isEqualTo("012345");
        assertThat(body.has("reqSMSAuthNo")).isFalse();
        assertThat(body.at("/accountList/0").has("reqSMSAuthNo")).isFalse();
        assertThat(body.at("/accountList/0").has("smsAuthNo")).isFalse();
        assertThat(body.at("/accountList/0/password").asText())
                .isEqualTo("synthetic-encrypted-password");
        assertThat(continuation.maskedDestination()).isEqualTo("010-****-0000");
    }

    @Test
    void explicitSmsContinuationAfterInputReturnsFreshContextWithoutPreviousCode() {
        var required = capture(response("smsAuthNo", "010-****-0000"));
        when(client.postOnce(anyString(), any()))
                .thenReturn(response("smsAuthNo", "010-****-0000"));
        var error =
                catchThrowableOfType(
                        AdditionalAuthenticationRequiredException.class,
                        () -> adapter.resume(adapter.acceptSms(required.payload(), "0123")));
        assertThat(error).isNotNull();
        var next = json.readTree(error.request().payload());
        assertThat(next.has("code")).isFalse();
        assertThat(next.at("/request").has("smsAuthNo")).isFalse();
        assertThat(next.at("/twoWayInfo/jti").asText()).isEqualTo("synthetic-jti");
        verify(client, times(1)).postOnce(anyString(), any());
    }

    @Test
    void resumesTransactionListWithExactSmsAtRoot() {
        Map<String, Object> transactionRequest =
                Map.of(
                        "connectedId", "synthetic-connected",
                        "organization", "0020",
                        "account", "synthetic-account",
                        "startDate", "20261001",
                        "endDate", "20261017",
                        "orderBy", "1",
                        "inquiryType", "1");
        AdditionalAuthenticationRequest challenge;
        try {
            adapter.requireIfSupported(
                    "/v1/kr/bank/p/account/transaction-list",
                    transactionRequest,
                    response("smsAuthNo", "010-****-0000"),
                    "0020");
            throw new AssertionError("Expected an explicit SMS continuation");
        } catch (AdditionalAuthenticationRequiredException required) {
            challenge = required.request();
        }

        var operation =
                new FinanceOperation("TRANSACTION_LIST", "0020", 1L, 1L, "2026-10", null, false,
                        java.time.LocalDateTime.of(2026, 10, 18, 3, 0), java.time.LocalDate.of(2026, 10, 18));
        byte[] accepted =
                adapter.acceptSms(adapter.prepareOperation(challenge.payload(), operation), "2453");
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
                                {"result":{"code":"CF-00000"},"data":{"resTrHistoryList":[]}}
                                """));

        var resumed = adapter.resumeOperation(accepted);
        assertThat(resumed.transactions()).isEmpty();
        assertThat(resumed.operation()).isEqualTo(operation);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(client, times(1))
                .postOnce(eq("/v1/kr/bank/p/account/transaction-list"), payload.capture());
        var body = json.valueToTree(payload.getValue());
        assertThat(body.path("is2Way").asBoolean()).isTrue();
        assertThat(body.at("/twoWayInfo/jti").asText()).isEqualTo("synthetic-jti");
        assertThat(body.path("smsAuthNo").asText()).isEqualTo("2453");
        assertThat(body.has("reqSMSAuthNo")).isFalse();
        assertThat(body.path("accountList").isMissingNode()).isTrue();
        assertThat(body.path("connectedId").asText()).isEqualTo("synthetic-connected");
    }

    @Test
    void legacyContextFieldStillSendsSmsToDocumentedInputField() {
        var c =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(capture(response("smsAuthNo", "010-****-0000")).payload());
        c.put("field", "reqSMSAuthNo");
        var accepted = adapter.acceptSms(json.writeValueAsBytes(c), "0123");
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"synthetic-connected",
 "successList":[{"organization":"0020"}],"errorList":[]}}
"""));

        adapter.resume(accepted);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(client).postOnce(eq("/v1/account/create"), payload.capture());
        var body = json.valueToTree(payload.getValue());
        assertThat(body.path("smsAuthNo").asText()).isEqualTo("0123");
        assertThat(body.has("reqSMSAuthNo")).isFalse();
    }

    @Test
    void documentedSmsMethodDoesNotRequireOptionalPhoneMetadata() {
        var response =
                (tools.jackson.databind.node.ObjectNode) response("smsAuthNo", "010-****-0000");
        ((tools.jackson.databind.node.ObjectNode) response.path("data")).remove("extraInfo");

        var challenge = capture(response);

        assertThat(challenge.maskedDestination()).isNull();
        assertThat(json.readTree(challenge.payload()).path("field").asText())
                .isEqualTo("smsAuthNo");
    }

    @Test
    void neverReturnsUnmaskedProviderDestinationOrRawContext() {
        var c = capture(response("sms", "01000000000"));
        assertThat(c.maskedDestination()).isNull();
        assertThat(c.toString()).doesNotContain("jti", "password", "01000000000");
    }

    @Test
    void codeAloneOrUnsupportedMethodCannotCreateSmsChallenge() {
        adapter.requireIfSupported(
                "/v1/account/create",
                request,
                json.readTree("{\"result\":{\"code\":\"CF-04034\"}}"),
                "0020");
        adapter.requireIfSupported(
                "/v1/account/create", request, response("ars", "010-****-0000"), "0020");
        adapter.requireIfSupported(
                "https://outside.example.test", request, response("sms", "010-****-0000"), "0020");
        var missing =
                (tools.jackson.databind.node.ObjectNode)
                        response("sms", "010-****-0000").path("data");
        missing.remove("jti");
        adapter.requireIfSupported("/v1/account/create", request, missing, "0020");
        verifyNoInteractions(client);
    }

    @Test
    void invalidSmsIsRejectedBeforeProviderCall() {
        byte[] context = capture(response("sms", "010-****-0000")).payload();
        for (String code : List.of("123", "123456789", "12x456", "USER_CANCEL"))
            assertThatThrownBy(() -> adapter.acceptSms(context, code))
                    .isInstanceOf(ApplicationException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsAnInjectedPathEvenInsideEncryptedContext() {
        var node =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(capture(response("sms", "010-****-0000")).payload());
        node.put("path", "/arbitrary/provider/operation");
        assertThatThrownBy(() -> adapter.acceptSms(json.writeValueAsBytes(node), "123456"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void clearProviderFailureRetainsCodeForExistingCertificateEventContract() {
        byte[] accepted =
                adapter.acceptSms(capture(response("sms", "010-****-0000")).payload(), "123456");
        when(client.postOnce(anyString(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-04000"},"data":{"errorList":[{"organization":"0020","code":"CF-12800"}]}}
"""));
        assertThat(adapter.resume(accepted).failureCode()).isEqualTo("CF-12800");
    }
    @Test
    void explicitProviderRejection_hasDedicatedFailureContract() {
        var required = capture(response("smsAuthNo", "010-****-0000"));
        byte[] accepted = adapter.acceptSms(adapter.prepareOperation(required.payload(),
                new FinanceOperation("BANK_CONNECT", "0020", null, null, null, null, false)), "0123");
        when(client.postOnce(anyString(), any())).thenReturn(
                json.readTree("{\"result\":{\"code\":\"CF-12800\"},\"data\":{}}"));
        assertThatThrownBy(() -> adapter.resumeOperation(accepted))
                .isInstanceOf(com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationRejectedException.class)
                .extracting(e -> ((com.amateur.howmuchbackend.common.exception.ApplicationException) e).getStatusCode().getCode())
                .isEqualTo("BANK_CREDENTIAL_INVALID");
    }

    @Test
    void transportFailure_doesNotBecomeExplicitProviderRejection() {
        var required = capture(response("smsAuthNo", "010-****-0000"));
        byte[] accepted = adapter.acceptSms(adapter.prepareOperation(required.payload(),
                new FinanceOperation("BANK_CONNECT", "0020", null, null, null, null, false)), "0123");
        var failure = com.amateur.howmuchbackend.common.exception.ApplicationException.of(
                com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE);
        when(client.postOnce(anyString(), any())).thenThrow(failure);
        assertThatThrownBy(() -> adapter.resumeOperation(accepted)).isSameAs(failure)
                .isNotInstanceOf(com.amateur.howmuchbackend.application.external.model.AdditionalAuthenticationRejectedException.class);
    }

}
