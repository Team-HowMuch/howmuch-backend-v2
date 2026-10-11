package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

public final class CodefFailureMapper {
    private CodefFailureMapper() {
    }

    static ApplicationException map(String code, StatusCode fallback, Throwable cause) {
        StatusCode status = status(code, fallback);
        return cause == null ? ApplicationException.of(status) : ApplicationException.of(status, cause);
    }

    /** Pure boundary conversion, shared by HTTP adapters and persisted provider-result reads. */
    public static StatusCode status(String code, StatusCode fallback) {
        return switch (code == null ? "" : code) {
            case "CF-12302" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_PASSWORD_INVALID;
            case "CF-12300", "CF-12301", "CF-11205" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_FILE_INVALID;
            case "CF-12805", "CF-12813", "CF-04030" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_REGISTRATION_REQUIRED;
            case "CF-12806" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_EXPIRED;
            case "CF-12807", "CF-12808", "CF-12819" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_UNAVAILABLE;
            case "CF-12809", "CF-12810", "CF-12814" -> ExternalConnectionStatusCode.BANK_CERTIFICATE_INVALID;
            case "CF-12800", "CF-12801", "CF-12803", "CF-12861" -> ExternalConnectionStatusCode.BANK_CREDENTIAL_INVALID;
            case "CF-12802", "CF-12834" -> ExternalConnectionStatusCode.BANK_CREDENTIAL_LOCKED;
            case "CF-12920", "CF-12922", "CF-12923", "CF-12924", "CF-12925", "CF-12926",
                 "CF-13030", "CF-13031", "CF-13033", "CF-13034", "CF-13035" -> ExternalConnectionStatusCode.BANK_ACCOUNT_PASSWORD_INVALID;
            case "CF-12921", "CF-13032" -> ExternalConnectionStatusCode.BANK_ACCOUNT_PASSWORD_LOCKED;
            case "CF-12094", "CF-12910", "CF-12911", "CF-12912", "CF-13013", "CF-13020",
                 "CF-13021", "CF-13022", "CF-13023", "CF-13024" -> ExternalConnectionStatusCode.BANK_ACCOUNT_NUMBER_INVALID;
            case "CF-13005", "CF-13060", "CF-13061", "CF-13062", "CF-13063", "CF-13064" -> ExternalConnectionStatusCode.BANK_BIRTH_DATE_INVALID;
            case "CF-12804" -> ExternalConnectionStatusCode.BANK_IDENTITY_MISMATCH;
            case "CF-12811", "CF-12812", "CF-12815", "CF-12816", "CF-12817", "CF-12818" -> ExternalConnectionStatusCode.BANK_SERVICE_UNAVAILABLE;
            case "CF-13001" -> ExternalConnectionStatusCode.BANK_QUERY_PERIOD_INVALID;
            case "CF-04008", "CF-04014", "CF-04015", "CF-04019" -> ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED;
            case "CF-00004", "CF-00005", "CF-00010", "CF-00013", "CF-00014", "CF-00015",
                 "CF-00017", "CF-00026", "CF-04020", "CF-04028" -> ExternalConnectionStatusCode.CODEF_CONFIGURATION_INVALID;
            case "CF-03002", "CF-04034", "CF-04035" -> ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED;
            case "CF-12001" -> ExternalConnectionStatusCode.CODEF_USER_ACTION_TIMEOUT;
            case "CF-00012", "CF-00022", "CF-00023" -> ExternalConnectionStatusCode.CODEF_RATE_LIMITED;
            case "CF-00011", "CF-00016", "CF-00018", "CF-01001", "CF-01002", "CF-01003",
                 "CF-01004", "CF-01005", "CF-01006", "CF-01007", "CF-09999", "CF-12203" ->
                    ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE;
            case "CF-04000" -> ExternalConnectionStatusCode.BANK_CONNECTION_FAILED;
            default -> fallback;
        };
    }
}
