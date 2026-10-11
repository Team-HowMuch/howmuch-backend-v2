package com.amateur.howmuchbackend.adapter.out.codef;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

class CodefFailureMapperTest {
    @ParameterizedTest
    @CsvSource({
        "CF-12302,BANK_CERTIFICATE_PASSWORD_INVALID", "CF-11205,BANK_CERTIFICATE_FILE_INVALID",
        "CF-12806,BANK_CERTIFICATE_EXPIRED", "CF-12807,BANK_CERTIFICATE_UNAVAILABLE",
        "CF-12805,BANK_CERTIFICATE_REGISTRATION_REQUIRED", "CF-12810,BANK_CERTIFICATE_INVALID",
        "CF-12801,BANK_CREDENTIAL_INVALID", "CF-12802,BANK_CREDENTIAL_LOCKED",
        "CF-13031,BANK_ACCOUNT_PASSWORD_INVALID", "CF-12920,BANK_ACCOUNT_PASSWORD_INVALID",
        "CF-13032,BANK_ACCOUNT_PASSWORD_LOCKED", "CF-12921,BANK_ACCOUNT_PASSWORD_LOCKED",
        "CF-13013,BANK_ACCOUNT_NUMBER_INVALID", "CF-13061,BANK_BIRTH_DATE_INVALID",
        "CF-12804,BANK_IDENTITY_MISMATCH", "CF-12812,BANK_SERVICE_UNAVAILABLE",
        "CF-13001,BANK_QUERY_PERIOD_INVALID", "CF-04019,BANK_ACCOUNT_RECONNECT_REQUIRED",
        "CF-03002,CODEF_ADDITIONAL_AUTH_REQUIRED", "CF-12001,CODEF_USER_ACTION_TIMEOUT",
        "CF-00012,CODEF_RATE_LIMITED", "CF-01004,CODEF_UPSTREAM_UNAVAILABLE",
        "CF-04020,CODEF_CONFIGURATION_INVALID", "CF-04000,BANK_CONNECTION_FAILED",
        "CF-99998,CODEF_UNMAPPED_ERROR"
    })
    void officialCodesDistinguishUserActionsFromServiceProblems(String code, ExternalConnectionStatusCode expected) {
        var error = CodefFailureMapper.map(code, ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR,
                new IllegalStateException("synthetic-private-response"));
        assertThat(error.getStatusCode()).isEqualTo(expected);
        assertThat(error.getMessage()).isEqualTo(expected.getMessage())
                .doesNotContain(code, "synthetic-private-response");
    }

    @Test
    void publishedCertificateErrorMessagesRemainCompatibleWithServer() throws IOException {
        try (var resource = getClass().getResourceAsStream("/contracts/certificate-user-errors.json")) {
            assertThat(resource).as("Published certificate app error contract").isNotNull();
            var messages = new ObjectMapper().readTree(resource);
            for (var status : ExternalConnectionStatusCode.values())
                assertThat(messages.path(status.getCode()).asText()).isEqualTo(status.getMessage());
            for (var status : CertificateStatusCode.values())
                assertThat(messages.path(status.getCode()).asText()).isEqualTo(status.getMessage());
            assertThat(messages.size()).isEqualTo(ExternalConnectionStatusCode.values().length + CertificateStatusCode.values().length);
        }
    }
}
