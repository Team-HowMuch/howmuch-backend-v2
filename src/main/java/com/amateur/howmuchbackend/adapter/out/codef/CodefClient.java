package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Component
public class CodefClient {
    private final RestClient restClient;
    private final CodefTokenClient tokenClient;
    private final ObjectMapper objectMapper;

    public CodefClient(
            @Qualifier("codefApiRestClient") RestClient restClient,
            CodefTokenClient tokenClient,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.tokenClient = tokenClient;
        this.objectMapper = objectMapper;
    }

    JsonNode post(String path, Object requestBody) {
        return post(path, requestBody, false);
    }

    /** Certificate creation must not be repeated, including after an HTTP 401. */
    JsonNode postOnce(String path, Object requestBody) {
        return post(path, requestBody, true);
    }

    JsonNode decode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (JacksonException ignored) {
            try {
                return objectMapper.readTree(URLDecoder.decode(responseBody, StandardCharsets.UTF_8));
            } catch (JacksonException | IllegalArgumentException exception) {
                throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE, exception);
            }
        }
    }

    private JsonNode post(String path, Object requestBody, boolean retried) {
        try {
            String rawResponse = restClient.post()
                    .uri(path)
                    .headers(headers -> headers.setBearerAuth(tokenClient.getAccessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(UriUtils.encode(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);
            return decode(rawResponse);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                tokenClient.clearCache();
                if (!retried) return post(path, requestBody, true);
            }
            throw new CodefHttpException(
                    exception.getStatusCode().value(), exception.getResponseBodyAsString(), exception
            );
        } catch (RestClientException exception) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE, exception);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("CODEF request body must be JSON serializable.", exception);
        }
    }

    static final class CodefHttpException extends RuntimeException {
        private final int status;
        private final String responseBody;

        CodefHttpException(int status, String responseBody, Throwable cause) {
            super("CODEF HTTP request failed with status " + status, cause);
            this.status = status;
            this.responseBody = responseBody;
        }

        int status() {
            return status;
        }

        String responseBody() {
            return responseBody;
        }
    }
}
