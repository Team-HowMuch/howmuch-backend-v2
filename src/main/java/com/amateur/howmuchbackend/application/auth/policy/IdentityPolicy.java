package com.amateur.howmuchbackend.application.auth.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.identity.AuthIdentity;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.Gender;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record IdentityPolicy(String hmacSecret) {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");
    private static final Pattern RESIDENT_NUMBER_PATTERN = Pattern.compile("^\\d{7}$");
    private static final DateTimeFormatter BIRTH_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    public AuthIdentity normalize(
            String name,
            String residentRegistrationNumber7,
            String phoneNumber
    ) {
        String normalizedName = normalizeName(name);
        ResidentIdentity residentIdentity = parseResidentIdentity(residentRegistrationNumber7);
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        String canonical = String.join(
                "|",
                normalizedName,
                residentIdentity.birthDate().format(BIRTH_DATE_FORMAT),
                residentIdentity.gender().name(),
                residentIdentity.number(),
                normalizedPhoneNumber
        );

        return new AuthIdentity(
                hmac("identity:" + canonical),
                normalizedName,
                residentIdentity.birthDate(),
                residentIdentity.gender(),
                normalizedPhoneNumber
        );
    }

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

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ApplicationException(AuthStatusCode.INVALID_AUTH_REQUEST);
        }
        return name.trim();
    }

    private ResidentIdentity parseResidentIdentity(String residentNumber) {
        if (!StringUtils.hasText(residentNumber) || !RESIDENT_NUMBER_PATTERN.matcher(residentNumber).matches()) {
            throw ApplicationException.of(AuthStatusCode.INVALID_RESIDENT_NUMBER);
        }

        int genderCode = residentNumber.charAt(6) - '0';
        int century = switch (genderCode) {
            case 1, 2 -> 1900;
            case 3, 4 -> 2000;
            default -> throw ApplicationException.of(AuthStatusCode.INVALID_RESIDENT_NUMBER);
        };
        Gender gender = switch (genderCode) {
            case 1, 3 -> Gender.MALE;
            case 2, 4 -> Gender.FEMALE;
            default -> throw ApplicationException.of(AuthStatusCode.INVALID_RESIDENT_NUMBER);
        };

        try {
            int year = century + Integer.parseInt(residentNumber.substring(0, 2));
            int month = Integer.parseInt(residentNumber.substring(2, 4));
            int day = Integer.parseInt(residentNumber.substring(4, 6));
            LocalDate birthDate = LocalDate.of(year, month, day);
            if (birthDate.isAfter(LocalDate.now())) {
                throw ApplicationException.of(AuthStatusCode.INVALID_RESIDENT_NUMBER);
            }
            return new ResidentIdentity(residentNumber, birthDate, gender);
        } catch (DateTimeException exception) {
            throw ApplicationException.of(AuthStatusCode.INVALID_RESIDENT_NUMBER);
        }
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

    private record ResidentIdentity(String number, LocalDate birthDate, Gender gender) {
    }
}
