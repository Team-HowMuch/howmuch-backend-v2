package com.amateur.howmuchbackend.adapter.out.codef;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

class CodefCertificateReferenceAdapterTest {
    final CodefClient client = mock(CodefClient.class);
    final CodefRsaEncryptor rsa = mock(CodefRsaEncryptor.class);
    final CodefObservability observation = mock(CodefObservability.class);
    final CodefBankConnectionAdapter adapter =
            new CodefBankConnectionAdapter(client, rsa, observation);
    final ObjectMapper json = new ObjectMapper();
    final CertificateReferenceSecret secret =
            new CertificateReferenceSecret("fixture", null, null, null);

    String response(String code, String extra) {
        return "{\"result\":{\"code\":\"CF-00000\"},\"data\":{\"connectedId\":\"fixture\","
                + "\"successList\":[],\"errorList\":[{\"organization\":\"0088\",\"code\":\""
                + code
                + "\""
                + extra
                + "}]}}";
    }

    @Test
    void referencePayloadUsesSourceAtTopAndTargetInListWithoutCertificateMaterial() {
        when(client.postOnce(eq("/v1/account/reference-add"), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"fixture","errorList":[],
"successList":[{"countryCode":"KR","businessType":"BK","clientType":"P","organization":"0088","loginType":"0","code":"CF-00000"}]}}
"""));
        adapter.addReference("fixture", "0020", "0088", secret);
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(client).postOnce(eq("/v1/account/reference-add"), payload.capture());
        verify(client, never()).post(any(), any());
        var body = json.valueToTree(payload.getValue());
        assertThat(body.path("organization").asText()).isEqualTo("0020");
        assertThat(body.at("/accountList/0/organization").asText()).isEqualTo("0088");
        assertThat(body.toString())
                .doesNotContain("derFile", "keyFile", "certType", "password", "loginType");
    }

    @Test
    void errorListOverridesOuterSuccess() {
        when(client.postOnce(any(), any())).thenReturn(json.readTree(response("CF-12805", "")));
        assertThatThrownBy(() -> adapter.addReference("fixture", "0020", "0088", secret))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .BANK_CERTIFICATE_REGISTRATION_REQUIRED));
    }

    @Test
    void additionalAuthenticationIsNotSuccess() {
        when(client.postOnce(any(), any()))
                .thenReturn(json.readTree(response("CF-03002", ",\"continue2Way\":true")));
        assertThatThrownBy(() -> adapter.addReference("fixture", "0020", "0088", secret))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                ExternalConnectionStatusCode
                                                        .CODEF_ADDITIONAL_AUTH_REQUIRED));
    }

    @Test
    void timeoutNeverRepeatsMutation() {
        when(client.postOnce(any(), any()))
                .thenThrow(
                        ApplicationException.of(
                                ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.addReference("fixture", "0020", "0088", secret))
                .isInstanceOf(CertificateReferenceUncertainException.class);
        verify(client, times(1)).postOnce(any(), any());
        verify(client, never()).post(any(), any());
    }

    @Test
    void switchedConnectedIdOrMissingTargetIsUncertain() {
        when(client.postOnce(any(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"another","successList":[],"errorList":[]}}
"""));
        assertThatThrownBy(() -> adapter.addReference("fixture", "0020", "0088", secret))
                .isInstanceOf(CertificateReferenceUncertainException.class);
    }

    @Test
    void listingDoesNotInferMissingAuthenticationType() {
        when(client.post(eq("/v1/account/list"), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"fixture","accountList":[
{"countryCode":"KR","businessType":"BK","clientType":"P","organization":"0020","loginType":"0"},
{"countryCode":"KR","businessType":"BK","clientType":"P","organization":"0088","loginType":"1"},
{"countryCode":"KR","businessType":"BK","clientType":"P","organization":"0004"},
{"countryCode":"KR","businessType":"BK","clientType":"B","organization":"0003","loginType":"0"}]}}
"""));
        var list = adapter.registeredAccounts("fixture");
        assertThat(list).hasSize(3);
        assertThat(list.stream().filter(a -> a.certificate()).count()).isEqualTo(1);
    }

    @Test
    void partialSuccessWithFailureRequiresReconciliation() {
        when(client.postOnce(any(), any()))
                .thenReturn(
                        json.readTree(
                                """
{"result":{"code":"CF-00000"},"data":{"connectedId":"fixture",
"successList":[{"organization":"0088","loginType":"0","code":"CF-00000"}],
"errorList":[{"organization":"0088","code":"CF-12805"}]}}
"""));
        assertThatThrownBy(() -> adapter.addReference("fixture", "0020", "0088", secret))
                .isInstanceOf(CertificateReferenceUncertainException.class);
    }
}
