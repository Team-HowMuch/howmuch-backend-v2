package com.amateur.howmuchbackend.application.certificate.policy;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.application.port.in.certificate.command.SubmitCertificateUploadCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.certificate.CertificateSession;

import java.time.LocalDateTime;

@org.springframework.stereotype.Component
public final class CertificateRegistrationPolicy {
    public void capacity(long activeSessions) {
        if (activeSessions >= 200) throw ApplicationException.of(CERTIFICATE_RATE_LIMITED);
    }

    public void active(CertificateSession s, LocalDateTime now) {
        if (!s.expiresAt().isAfter(now)) throw ApplicationException.of(CERTIFICATE_EXPIRED);
    }

    public boolean mayStart(
            CertificateSession s,
            com.amateur.howmuchbackend.application.port.in.certificate.command
                            .ReceiveCertificateCommand
                    c,
            LocalDateTime now) {
        return s != null
                && java.util.Set.of("SUBMITTED", "REGISTERING").contains(s.status())
                && s.version() == c.version()
                && s.organization().equals(c.organization())
                && java.util.Objects.equals(s.uploadRef(), c.uploadRef())
                && s.expiresAt().isAfter(now)
                && c.deadline().isAfter(now);
    }

    public boolean mayCleanup(CertificateSession s) {
        return s != null
                && !s.approved()
                && java.util.Set.of("CANCELLED", "EXPIRED", "FAILED").contains(s.status());
    }

    public boolean mayApply(
            CertificateSession s,
            com.amateur.howmuchbackend.application.port.in.certificate.command
                            .ReceiveCertificateResultCommand
                    e,
            LocalDateTime now) {
        return s != null
                && s.version() == e.version()
                && s.expiresAt().isAfter(now)
                && java.util.Set.of("SUBMITTED", "REGISTERING", "UNKNOWN").contains(s.status());
    }

    public void material(SubmitCertificateUploadCommand c) {
        if (c.der() == null
                || c.key() == null
                || c.der().length < 1
                || c.key().length < 1
                || c.der().length > 131072
                || c.key().length > 131072
                || c.password() == null
                || c.password().isEmpty()
                || c.password().length() > 256
                || !optional(c.accountNumber(), "[0-9-]{1,30}")
                || !optional(c.accountPassword(), "[0-9]{4,8}")
                || !birthDate(c.birthDate())
                || blank(c.accountNumber()) != blank(c.accountPassword()))
            throw ApplicationException.of(CERTIFICATE_INPUT_INVALID);
    }

    private boolean birthDate(String value) {
        if (blank(value)) return true;
        if (!value.matches("[0-9]{6}|[0-9]{8}")) return false;
        try {
            java.time.LocalDate.parse(
                    value.length() == 6 ? "20" + value : value,
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            return true;
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    private boolean optional(String v, String regex) {
        return blank(v) || v.matches(regex);
    }

    private boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
