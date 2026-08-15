package com.amateur.howmuchbackend.application.auth.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record IdentityPolicy(String hmacSecret) {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");
    public String normalizePhoneNumber(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PHONE_NUMBER);
        }

        String normalized = phoneNumber.replaceAll("[-\\s]", "");
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PHONE_NUMBER);
        }
        return normalized;
    }

    public String phoneHash(String phoneNumber) {
        return hmac("phone:" + normalizePhoneNumber(phoneNumber));
    }

    private String hmac(String value) {
        if (!StringUtils.hasText(hmacSecret)) {
            throw new IllegalStateException("howmuch.auth.identity.hmac-secret must be configured.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create identity HMAC.", exception);
        }
    }
}
