package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

final class CodefFailureMapper {
    private CodefFailureMapper() {
    }

    static ApplicationException map(String code, StatusCode fallback, Throwable cause) {
        StatusCode status = switch (code == null ? "" : code) {
            case "CF-12801" -> ExternalConnectionStatusCode.BANK_CREDENTIAL_INVALID;
            case "CF-12834" -> ExternalConnectionStatusCode.BANK_CREDENTIAL_LOCKED;
            case "CF-03002" -> ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED;
            case "CF-12001" -> ExternalConnectionStatusCode.CODEF_USER_ACTION_TIMEOUT;
            case "CF-00022", "CF-00023" -> ExternalConnectionStatusCode.CODEF_RATE_LIMITED;
            case "CF-00011", "CF-00016", "CF-00018", "CF-01001", "CF-01002", "CF-01003",
                 "CF-01004", "CF-01005", "CF-01006", "CF-01007", "CF-09999", "CF-12203" ->
                    ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE;
            case "CF-04000" -> ExternalConnectionStatusCode.BANK_CONNECTION_FAILED;
            default -> fallback;
        };
        return cause == null ? ApplicationException.of(status) : ApplicationException.of(status, cause);
    }
}
