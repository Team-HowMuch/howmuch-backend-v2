package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.infra.codef.config.CodefProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Component
public class CodefTokenClient {
    private static final String REQUEST_BODY = "grant_type=client_credentials&scope=read";

    private final RestClient restClient;
    private final CodefProperties properties;
    private volatile CachedToken cachedToken;

    public CodefTokenClient(
            @Qualifier("codefOAuthRestClient") RestClient restClient,
            CodefProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public String getAccessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.usable()) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current == null || !current.usable()) {
                cachedToken = requestAccessToken();
            }
            return cachedToken.value();
        }
    }

    public void clearCache() {
        cachedToken = null;
    }

    private CachedToken requestAccessToken() {
        if (!properties.hasClientCredentials()) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID);
        }
        try {
            JsonNode response = restClient.post()
                    .uri(properties.oauthUrl())
                    .header(HttpHeaders.AUTHORIZATION, basicAuthorization())
                    .body(REQUEST_BODY)
                    .retrieve()
                    .body(JsonNode.class);
            String token = response == null ? null : response.path("access_token").asText(null);
            long expiresIn = response == null ? 0 : response.path("expires_in").asLong(0);
            if (token == null || token.isBlank() || expiresIn <= 0) {
                throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE);
            }
            return new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            ExternalConnectionStatusCode status = exception.getStatusCode().is4xxClientError()
                    ? ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID
                    : ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE;
            throw ApplicationException.of(status, exception);
        } catch (RestClientException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE, exception);
        }
    }

    private String basicAuthorization() {
        String credentials = properties.clientId() + ":" + properties.clientSecret();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean usable() {
            return expiresAt.minusSeconds(60).isAfter(Instant.now());
        }
    }
}
