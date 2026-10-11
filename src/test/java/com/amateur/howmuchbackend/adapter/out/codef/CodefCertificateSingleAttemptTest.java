package com.amateur.howmuchbackend.adapter.out.codef;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

class CodefCertificateSingleAttemptTest {
    @Test
    void unauthorizedCreateClearsCachedTokenWithoutRepeatingPost() {
        var builder = RestClient.builder().baseUrl("https://codef.example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(CodefTokenClient.class);
        when(tokens.getAccessToken()).thenReturn("synthetic-token");
        var client = new CodefClient(builder.build(), tokens, new ObjectMapper());
        server.expect(requestTo("https://codef.example.test/v1/account/create"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.postOnce("/v1/account/create", Map.of()))
                .isInstanceOf(CodefClient.CodefHttpException.class);
        server.verify();
        verify(tokens, times(1)).getAccessToken();
        verify(tokens, times(1)).clearCache();
    }
}
