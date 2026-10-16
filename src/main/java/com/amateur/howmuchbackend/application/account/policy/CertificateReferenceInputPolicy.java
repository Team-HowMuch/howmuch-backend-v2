package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

@Component
public class CertificateReferenceInputPolicy {
    public StartCertificateReferenceConnectionCommand normalize(
            StartCertificateReferenceConnectionCommand c, LocalDate today) {
        if (c.idempotencyKey() == null
                || c.sourceOrganization() == null
                || c.organization() == null
                || !c.sourceOrganization().matches("[0-9]{4}")
                || !c.organization().matches("[0-9]{4}")
                || c.sourceOrganization().equals(c.organization())
                || c.connectionId() <= 0)
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.BANK_CERTIFICATE_REFERENCE_INVALID);
        String birth = blank(c.birthDate()), number = blank(c.accountNumber());
        String password = c.accountPassword();
        if (password != null && password.isBlank()) password = null;
        if (number != null) number = number.replace("-", "").trim();
        if ((number == null) != (password == null))
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_REQUIRED);
        if (number != null && (!number.matches("[0-9]{1,30}") || password.length() > 100))
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_NUMBER_INVALID);
        if (birth != null) {
            try {
                if (!birth.matches("[0-9]{6}|[0-9]{8}")) throw new IllegalArgumentException();
                String date = birth;
                if (birth.length() == 6)
                    date =
                            (Integer.parseInt(birth.substring(0, 2)) <= today.getYear() % 100
                                            ? "20"
                                            : "19")
                                    + birth;
                LocalDate parsed =
                        LocalDate.parse(
                                date,
                                DateTimeFormatter.ofPattern("uuuuMMdd")
                                        .withResolverStyle(ResolverStyle.STRICT));
                if (parsed.isAfter(today) || parsed.getYear() < 1900)
                    throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw ApplicationException.of(ExternalConnectionStatusCode.BANK_BIRTH_DATE_INVALID);
            }
        }
        return new StartCertificateReferenceConnectionCommand(
                c.connectionId(),
                c.idempotencyKey(),
                c.sourceOrganization(),
                c.organization(),
                birth,
                number,
                password);
    }

    public void validateRequirements(
            StartCertificateReferenceConnectionCommand c, CertificateInstitutionResult rule) {
        if (rule.requiresAccountCredentials() && c.accountNumber() == null)
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_REQUIRED);
        if (rule.birthDateRequired() && c.birthDate() == null)
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_BIRTH_DATE_INVALID);
    }

    private String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
